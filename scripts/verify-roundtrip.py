#!/usr/bin/env python3
"""Compare disassembled on-device-engine outputs with independently rebuilt smali patches."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
original, prepared = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
work = Path(tempfile.mkdtemp(prefix='roundtrip.', dir=root / 'build'))
tool = root / 'tools/apktool_3.0.3.jar'
def decode(apk, name):
    target = work / name
    subprocess.run(['java', '-jar', str(tool), 'd', '-r', '--no-assets', '-p', str(work / 'framework'),
                    '-o', str(target), str(apk)], check=True)
    return target / 'smali'

stock = decode(original, 'original')
expected_midi = decode(prepared / 'midi/unsigned.apk', 'expected-midi')
expected_both = decode(prepared / 'aaudio/unsigned.apk', 'expected-both')
checks = 0
for mask in (1, 2, 3):
    actual = decode(root / f'build/integration/variant-{mask}.apk', f'actual-{mask}')
    expected = expected_midi if mask == 1 else expected_both
    wanted = {p.relative_to(expected): p.read_text() for p in expected.rglob('*.smali')}
    if mask == 2:
        for rel in list(wanted):
            if str(rel).startswith('com/singlecellsoftware/caustic/midi/'):
                del wanted[rel]
        for p in (stock / 'com/singlecellsoftware/caustic/midi').rglob('*.smali'):
            wanted[p.relative_to(stock)] = p.read_text()
        activity = Path('com/singlecellsoftware/caustic/CausticActivity.smali')
        pattern = r'^\.method public HandleReturnCode\(I\)V\n.*?^\.end method'
        old = re.search(pattern, (stock / activity).read_text(), re.M | re.S)[0]
        wanted[activity] = re.sub(pattern, lambda m: old, wanted[activity], flags=re.M | re.S)
    result = {p.relative_to(actual): p.read_text() for p in actual.rglob('*.smali')}
    assert wanted.keys() == result.keys(), f'Variant {mask} class inventory differs'
    for path in wanted:
        assert wanted[path] == result[path], f'Variant {mask} changed unexpected bytecode: {path}'
        checks += 1
print(f'PASS: {checks} complete disassembled classes match independently rebuilt expectations across three variants.')
