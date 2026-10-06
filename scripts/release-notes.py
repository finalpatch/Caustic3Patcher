#!/usr/bin/env python3
"""Generate release notes from commits since the preceding reachable version tag."""
from pathlib import Path
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def generate(tag, repository):
    if not re.fullmatch(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', tag):
        raise ValueError('Expected a stable version tag')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Expected owner/repository')
    git('rev-parse', '--verify', tag + '^{commit}')
    tags = git('tag', '--merged', tag, '--sort=-version:refname').splitlines()
    version = lambda name: tuple(map(int, name[1:].split('.')))
    previous = next((name for name in tags
                     if re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', name)
                     and version(name) < version(tag)), None)
    revision = previous + '..' + tag if previous else tag
    commits = git('log', '--reverse', '--format=%h %s', revision).splitlines()
    heading = 'Changes since ' + previous if previous else 'Changes in ' + tag
    lines = ['## ' + heading, '']
    lines.extend('- ' + commit for commit in commits)
    if previous:
        lines += ['', f'[Full changelog](https://github.com/{repository}/compare/{previous}...{tag})']
    footer = Path('.github/release-notes.md').read_text().replace('vX.Y.Z', tag)
    return '\n'.join(lines) + '\n\n' + footer


if __name__ == '__main__':
    print(generate(sys.argv[1], sys.argv[2]), end='')
