"""Overlay replacement MIDI classes and patch one explicit-enable call site."""
from pathlib import Path
import shutil
import sys

work = Path(sys.argv[1])
smali = work / 'source/smali'
shutil.copytree(smali, work / 'reference-smali')
midi = Path('com/singlecellsoftware/caustic/midi')
replacement = work / 'bridge-decoded/smali' / midi
assert (replacement / 'MidiSidekick.smali').exists()
for path in (work / 'bridge-decoded/smali').rglob('*.smali'):
    assert path.is_relative_to(replacement), f'Unexpected compiled class: {path}'
shutil.rmtree(smali / midi)
shutil.copytree(replacement, smali / midi)
activity = smali / 'com/singlecellsoftware/caustic/CausticActivity.smali'
text = activity.read_text()
start = text.index('.method public HandleReturnCode(I)V')
end = text.index('.end method', start)
method = text[start:end]
call = 'Lcom/singlecellsoftware/caustic/midi/MidiSidekick;->Start()V'
assert method.count(call) == 1, 'Explicit enable call site changed'
method = method.replace(call, call.replace('Start()', 'StartWithPicker()'))
activity.write_text(text[:start] + method + text[end:])
print('Replaced MIDI package and patched exactly one explicit-enable call.')
