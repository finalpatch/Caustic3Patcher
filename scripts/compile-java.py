import pathlib
import subprocess
import sys

sources = sorted(str(p) for root in ['app/src/main/java', 'core/src', 'build/app/generated'] for p in pathlib.Path(root).rglob('*.java'))
classpath = sys.argv[1] + ':' + ':'.join(str(p) for p in sorted(pathlib.Path('vendor').glob('*.jar')))
subprocess.run(['javac', '--release', '8', '-cp', classpath, '-d', 'build/app/classes', *sources], check=True)
subprocess.run(['jar', 'cf', 'build/app/classes.jar', '-C', 'build/app/classes', '.'], check=True)
