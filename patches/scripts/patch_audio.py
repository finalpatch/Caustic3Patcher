"""Apply the accepted MIDI repair, then the counted stage-2 audio call redirects."""
from pathlib import Path
import os
import runpy
import shutil
import sys

work = Path(sys.argv[1])
runpy.run_path('scripts/patch_repair.py', run_name='__main__')
root = work / 'source/smali'
new_owner = 'Lcom/singlecellsoftware/caustic/audio/AudioBackend;'
old_owner = 'Lcom/singlecellsoftware/OpenSLIO;'
audio = work / 'audio-decoded/smali'
for path in audio.rglob('*.smali'):
    relative = path.relative_to(audio)
    assert str(relative).startswith('com/singlecellsoftware/caustic/audio/'), relative
    target = root / relative
    assert not target.exists(), target
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, target)

for name, count in [('AudioEngine', 6), ('CausticActivity', 2)]:
    path = root / f'com/singlecellsoftware/caustic/{name}.smali'
    text = path.read_text()
    assert text.count(old_owner) == count, f'{name}: changed OpenSL call inventory'
    text = text.replace(old_owner, new_owner)
    if name == 'CausticActivity':
        marker = ('    sput-object p0, Lcom/singlecellsoftware/caustic/CausticActivity;'
                  '->m_CausticActivity:Lcom/singlecellsoftware/caustic/CausticActivity;')
        assert text.count(marker) == 1
        text = text.replace(marker, '    invoke-static {p0}, ' + new_owner +
                            '->initialize(Landroid/content/Context;)V\n\n' + marker)
    path.write_text(text)
if os.environ.get('CAUSTIC_AUDIO_MODE') in ('aaudio', 'recovery'):
    from audio_stage3_patch import engine_patch, loop_patch
    for name, transform in [('AudioEngine', lambda text: engine_patch(text, recovery=os.environ.get('CAUSTIC_AUDIO_MODE') == 'recovery')), ('OutputAudioLoop', loop_patch)]:
        path = root / f'com/singlecellsoftware/caustic/{name}.smali'
        path.write_text(transform(path.read_text()))

shutil.copyfile(work / 'native/libcaustic_audio.so',
                work / 'source/lib/arm64-v8a/libcaustic_audio.so')
print('Audio patch: 8 call owners redirected; 1 initialization call; original engine untouched.')
