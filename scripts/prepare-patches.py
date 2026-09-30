#!/usr/bin/env python3
"""Build modular overlays from the pinned original and the authored repair sources.
Only build hosts need Apktool, Java, D8, aapt2 and an ARM64 Android NDK.
"""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
original = Path(sys.argv[1]).resolve()
assert hashlib.sha256(original.read_bytes()).hexdigest() == '7cf80508530e041821ab04693c6fc7bbd1fcd4c4b598cef825d3fd212b568ebf', 'Unsupported original'
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk')))
ndk = Path(os.environ.get('ANDROID_NDK_HOME', str(Path.home() / 'android-ndk-r29')))
apktool = Path(os.environ.get('APKTOOL_JAR', ROOT / 'tools/apktool_3.0.3.jar')).resolve()
assert hashlib.sha256(apktool.read_bytes()).hexdigest() == 'dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423', 'Expected pinned Apktool 3.0.3'
android = sdk / 'platforms/android-36/android.jar'
(ROOT / 'build').mkdir(exist_ok=True)
work = Path(tempfile.mkdtemp(prefix='patches.', dir=ROOT / 'build'))
assets = ROOT / 'app/src/main/assets/patches'

def run(*args, cwd=ROOT, env=None):
    print('Running:', args[0], args[1] if len(args) > 1 else '', flush=True)
    subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True)

def decode(apk, dest):
    run('java', '-jar', apktool, 'd', '-r', '-p', work / 'framework', '-o', dest, apk)

for name, package in [('bridge', 'midi'), ('audio', 'audio')]:
    classes, dex = work / (name + '-classes'), work / (name + '-dex')
    classes.mkdir(); dex.mkdir()
    sources = sorted((ROOT / 'patches/src/com/singlecellsoftware/caustic' / package).glob('*.java'))
    run('javac', '--release', '8', '-classpath', android, '-d', classes, *sources)
    run('jar', 'cf', work / (name + '.jar'), '-C', classes, '.')
    run(sdk / 'build-tools/36.0.0/d8', '--release', '--min-api', '30', '--lib', android, '--output', dex, work / (name + '.jar'))
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(work / (name + '.apk'), 'w') as target:
        for entry in ('AndroidManifest.xml', 'resources.arsc'):
            target.writestr(entry, source.read(entry))
        target.write(dex / 'classes.dex', 'classes.dex')
    decode(work / (name + '.apk'), work / (name + '-decoded'))

native = work / 'native'; native.mkdir()
host = os.environ.get('NDK_HOST_TAG', 'linux-aarch64')
cxx = ndk / f'toolchains/llvm/prebuilt/{host}/bin/aarch64-linux-android30-clang++'
run(cxx, '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror', '-fno-exceptions', '-fno-rtti', '-nostdlib++',
    '-fPIC', '-fvisibility=hidden', '-shared', '-Wl,--no-undefined,-z,relro,-z,now,-z,max-page-size=16384',
    '-DCAUSTIC_AAUDIO', '-DCAUSTIC_RECOVERY', ROOT / 'patches/src/native/caustic_audio.cpp',
    '-o', native / 'libcaustic_audio.so', '-llog', '-ldl', '-laaudio')

cp = 'vendor/dexlib2-2.5.2.jar:vendor/guava-27.1-android.jar'
(work / 'tools').mkdir()
run('javac', '--release', '8', '-cp', cp, '-d', work / 'tools',
    'core/src/org/caustic/patcher/core/DexPatches.java', 'tools/src/OverlayBuilder.java')

def members(path):
    text = path.read_text()
    owner = next(line.split()[-1] for line in text.splitlines() if line.startswith('.class '))
    methods = {m[1].split()[-1]: m[0] for m in re.finditer(r'^\.method ([^\n]+)\n.*?^\.end method', text, re.M | re.S)}
    fields = {line.split()[-1]: line for line in text.splitlines() if line.startswith('.field ')}
    return owner, methods, fields

for mode in ('midi', 'aaudio'):
    variant = work / mode; variant.mkdir()
    decode(original, variant / 'source')
    # The upstream audio transformation includes MIDI. Extract only audio members below.
    for name in ('bridge-decoded', 'audio-decoded', 'native'):
        (variant / name).symlink_to(work / name, target_is_directory=True)
    env = dict(os.environ, CAUSTIC_AUDIO_MODE='recovery')
    run('python', 'scripts/patch_repair.py' if mode == 'midi' else 'scripts/patch_audio.py', variant,
        cwd=ROOT / 'patches', env=env)
    run('java', '-jar', apktool, 'b', '-p', work / 'framework', '--aapt', shutil.which('aapt2'),
        '-o', variant / 'unsigned.apk', variant / 'source')
    selection = []
    for path in sorted((variant / 'source/smali').rglob('*.smali')):
        rel = path.relative_to(variant / 'source/smali')
        owner, methods, fields = members(path)
        is_midi = str(rel).startswith('com/singlecellsoftware/caustic/midi/')
        if is_midi:
            if mode == 'midi': selection.append(owner)
            continue
        before = variant / 'reference-smali' / rel
        if not before.exists():
            assert mode == 'aaudio' and '/audio/' in str(rel)
            selection.append(owner); continue
        _, old_methods, old_fields = members(before)
        assert old_methods.keys() <= methods.keys(), 'Deleted methods need an explicit removal design'
        for key, value in methods.items():
            if value != old_methods.get(key):
                if mode == 'aaudio' and key == 'HandleReturnCode(I)V': continue
                selection.append(owner + '->' + key)
        for key, value in fields.items():
            if value != old_fields.get(key): selection.append(owner + '->' + key)
    listing = variant / 'members.txt'; listing.write_text('\n'.join(selection) + '\n')
    with zipfile.ZipFile(variant / 'unsigned.apk') as apk:
        (variant / 'classes.dex').write_bytes(apk.read('classes.dex'))
    run('java', '-cp', str(work / 'tools') + ':' + cp, 'OverlayBuilder', variant / 'classes.dex', listing, assets / (mode + '.dex'))
    shutil.copyfile(listing, assets / (mode + '-members.txt'))

shutil.copyfile(native / 'libcaustic_audio.so', assets / 'libcaustic_audio.so')
names = ['midi.dex', 'aaudio.dex', 'libcaustic_audio.so']
(assets / 'checksums.properties').write_text(''.join(name + '=' + hashlib.sha256((assets / name).read_bytes()).hexdigest() + '\n' for name in names))
print('Generated verified-input patch assets in', assets)
