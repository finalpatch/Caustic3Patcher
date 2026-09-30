"""Exact, bounded synchronization changes for the AAudio proof variant."""
import re

OWNER = 'Lcom/singlecellsoftware/caustic/AudioEngine;'
BRIDGE = 'Lcom/singlecellsoftware/caustic/audio/AudioBackend;'
SYNC = ('Start()V', 'Stop()V', 'Pause()V', 'Resume()V', 'SetAPI(I)V',
        'SetInternalLatency(I)V', 'SetForceUseMic(Z)V', 'EnableRecording(Z)V')

def engine_patch(text, recovery=False):
    # Wrappers keep the monitor register separate from original parameter reuse.
    wrappers = []
    for signature in SYNC:
        old = '.method public ' + signature
        assert text.count(old + '\n') == 1
        name, tail = signature.split('(', 1)
        target = name + 'AudioImpl(' + tail
        text = text.replace(old + '\n', '.method private ' + target + '\n')
        args = 'p0' if tail == ')V' else 'p0, p1'
        wrappers.append(f'''{old}
    .locals 1
    monitor-enter p0
    :audio_try
    invoke-direct {{{args}}}, {OWNER}->{target}
    :audio_end
    .catchall {{:audio_try .. :audio_end}} :audio_catch
    monitor-exit p0
    return-void
    :audio_catch
    move-exception v0
    monitor-exit p0
    throw v0
.end method
''')
    marker = '.method private StartOutput()V\n    .locals 3\n'
    assert text.count(marker) == 1
    text = text.replace(marker, marker + f'''
    iget-boolean v0, p0, {OWNER}->m_bPlaybackStarted:Z
    if-eqz v0, :audio_start
    return-void
    :audio_start
''')
    method = re.search(r'\.method private StopOutput\(\)V\n.*?\.end method', text, re.S).group()
    start = method.index('    .line 405\n')
    end = method.index('    :cond_2\n', start)
    assert '0x77359400' in method[start:end]
    changed = method[:start] + f'    invoke-static {{v0}}, {BRIDGE}->awaitOutputThread(Ljava/lang/Thread;)V\n\n' + method[end:]
    text = text.replace(method, changed)
    if recovery:
        # AAudio publishes its granted buffer estimate before callbacks start.
        # Remove only the OpenSL branch's hard-coded 192-frame latency setter.
        old_latency = ('    const/16 v0, 0xc0\n\n    .line 297\n'
                       '    invoke-static {v0}, Lcom/singlecellsoftware/caustic/CausticNative;->SetLatency(I)V\n')
        assert text.count(old_latency) == 1
        text = text.replace(old_latency, '')
    # Logs accurately identify the replacement; native UI naming is stage 5.
    text = text.replace('OpenSLES sound engine', 'AAudio sound engine' if recovery else 'AAudio proof sound engine')
    return text + '\n' + '\n'.join(wrappers)

def loop_patch(text):
    for field in ('m_bFinished', 'm_bProcess', 'm_bRun'):
        old = f'.field public {field}:Z'
        assert text.count(old) == 1
        text = text.replace(old, f'.field public volatile {field}:Z')
    store = '    iput-boolean v0, p0, Lcom/singlecellsoftware/caustic/OutputAudioLoop;->m_bRun:Z'
    assert text.count(store) == 2
    # Publish run=true before Thread.start; the worker must never undo a stop.
    text = text.replace(store, '    const/4 v0, 0x1\n\n' + store + '\n\n    const/4 v0, 0x0', 1)
    run = text.index('.method public run()V')
    text = text[:run] + text[run:].replace(store, '    # Run flag was published by the constructor.', 1)
    return text
