from pathlib import Path
import hashlib

for line in Path('vendor/SHA256SUMS').read_text().splitlines():
    digest, filename = line.split()
    assert hashlib.sha256(Path(filename).read_bytes()).hexdigest() == digest, filename + ' hash mismatch'
