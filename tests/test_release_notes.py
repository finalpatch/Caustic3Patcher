import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_notes', Path(__file__).resolve().parents[1] / 'scripts/release-notes.py')
notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notes)


class ReleaseNotesTest(unittest.TestCase):
    def test_first_release_and_only_commits_since_previous_version(self):
        original = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                def git(*args):
                    return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL, text=True)
                git('init')
                git('config', 'user.name', 'Test')
                git('config', 'user.email', 'test@example.invalid')
                Path('.github').mkdir()
                Path('.github/release-notes.md').write_text('Download vX.Y.Z\n')
                git('add', '.')
                git('commit', '-m', 'Initial release')
                git('tag', 'v0.1.1')
                first = notes.generate('v0.1.1', 'owner/repo')
                self.assertIn('Initial release', first)
                self.assertIn('Download v0.1.1', first)
                git('commit', '--allow-empty', '-m', 'Add graphics pacing')
                git('tag', 'v0.1.2')
                git('commit', '--allow-empty', '-m', 'Future change')
                git('tag', 'v0.1.3')
                result = notes.generate('v0.1.2', 'owner/repo')
                self.assertIn('Changes since v0.1.1', result)
                self.assertIn('Add graphics pacing', result)
                self.assertNotIn('Initial release', result)
                self.assertNotIn('Future change', result)
                self.assertIn('/compare/v0.1.1...v0.1.2', result)
                self.assertIn('Download v0.1.2', result)
                with self.assertRaises(ValueError):
                    notes.generate('--all', 'owner/repo')
            finally:
                os.chdir(original)
