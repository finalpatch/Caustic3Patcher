import base64
import importlib.util
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / 'scripts' / (name + '.py'))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


version = module('version')
keys = module('ci-keystore')


class ReleaseTests(unittest.TestCase):
    def test_version_progression(self):
        versions = ['0.1.1', '0.1.2', '0.2.0', '0.999.999', '1.0.0', '2099.999.999']
        codes = [version.parse_version(v)[1] for v in versions]
        self.assertEqual(codes, sorted(set(codes)))
        self.assertEqual(version.parse_version('v0.1.1', tag=True), ('0.1.1', 1001))
        self.assertEqual(version.parse_version('0.1.1'), version.parse_version('v0.1.1', tag=True))
        self.assertLess(codes[-1], 2100000000)

    def test_invalid_tags(self):
        for tag in ['0.1.1', 'v0.0.0', 'v01.2.3', 'v1.1000.0', 'v1.0.1000', 'v2100.0.0',
                    'v1.2.3-beta', 'v1.2', 'v1.2.3\n', 'v1.2.3;echo bad', 'v$(id)', '']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version.parse_version(tag, tag=True)

    def test_signing_secret_validation(self):
        data = b'fixture-only, not a private key'
        encoded = base64.b64encode(data).decode()
        password = 'sp ace $ quotes \' " \\ test'
        self.assertEqual(keys.decode_secrets(encoded + '\n', password), (data, password.encode()))
        for value, secret in [('', 'pw'), (encoded, ''), ('not!base64', 'pw'), ('   ', 'pw'), (encoded, 'pw\nsecond')]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                keys.decode_secrets(value, secret)

    def test_ci_cannot_generate_development_key(self):
        env = dict(os.environ)
        env.pop('PATCHER_KEYSTORE', None)
        env.pop('PATCHER_KEY_PASSWORD_FILE', None)
        env.update(CI='true', RELEASE_TAG='v0.1.1')
        result = subprocess.run(['sh', 'scripts/build.sh'], cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Release signing requires', result.stderr)


if __name__ == '__main__':
    unittest.main()
