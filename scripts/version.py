"""One release version scheme for tagged and local command-line builds."""
from pathlib import Path
import os
import re
import sys


def parse_version(value, *, tag=False):
    pattern = r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)'
    match = re.fullmatch(('v' if tag else '') + pattern, value)
    if not match:
        raise ValueError('Expected a version tag vMAJOR.MINOR.PATCH' if tag else 'Expected MAJOR.MINOR.PATCH')
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch
    if major > 2099 or minor > 999 or patch > 999 or code < 1:
        raise ValueError('Version components exceed supported Android versionCode range')
    return f'{major}.{minor}.{patch}', code


def build_version():
    if 'RELEASE_TAG' in os.environ:
        return parse_version(os.environ['RELEASE_TAG'], tag=True)
    return parse_version((Path(__file__).resolve().parents[1] / 'VERSION').read_text().strip())


if __name__ == '__main__':
    try:
        name, code = build_version()
    except ValueError as error:
        sys.exit(str(error))
    print(name, code)
