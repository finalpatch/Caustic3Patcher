"""Restore Actions signing secrets without printing their contents."""
import base64
import binascii
import os
from pathlib import Path
import sys
import tempfile


def decode_secrets(encoded, password):
    if not encoded or not password:
        raise ValueError('Both PATCHER_KEYSTORE_BASE64 and PATCHER_KEYSTORE_PASSWORD secrets are required')
    if '\n' in password or '\r' in password:
        raise ValueError('Keystore password must be a single line')
    try:
        data = base64.b64decode(''.join(encoded.split()), validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError('PATCHER_KEYSTORE_BASE64 is not valid base64') from error
    if not data:
        raise ValueError('Keystore is empty')
    return data, password.encode('utf-8')


if __name__ == '__main__':
    try:
        data, password = decode_secrets(os.environ.get('PATCHER_KEYSTORE_BASE64', ''),
                                       os.environ.get('PATCHER_KEYSTORE_PASSWORD', ''))
    except ValueError as error:
        sys.exit(str(error))
    os.umask(0o077)
    folder = Path(tempfile.mkdtemp(prefix='patcher-signing-', dir=os.environ['RUNNER_TEMP']))
    keystore, password_file = folder / 'release.p12', folder / 'password'
    keystore.write_bytes(data)
    password_file.write_bytes(password)
    with open(os.environ['GITHUB_ENV'], 'a') as env:
        env.write(f'PATCHER_KEYSTORE={keystore}\nPATCHER_KEY_PASSWORD_FILE={password_file}\n')
    print('Release signing files restored to private runner temporary storage.')
