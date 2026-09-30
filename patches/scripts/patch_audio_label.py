"""Pinned native UI label edit shared by AAudio proof and recovery builds."""
import hashlib

OFFSET = 0x44bed
ORIGINAL_SHA256 = 'd74dc1a15178ff178d14a8d9b1fa1cf31a12cfe7af2db7d67814481eb9250b1d'
PATCHED_SHA256 = 'd815864d7bd041d29bcedf776ed7e5b0efd334d8522fb2d7a9734c5cd95ea60f'
BEFORE = b'OpenSL ES\0'
AFTER = b'AAudio\0\0\0\0'

def patch_engine(engine):
    if hashlib.sha256(engine).hexdigest() != ORIGINAL_SHA256 or engine[OFFSET:OFFSET + len(BEFORE)] != BEFORE:
        raise ValueError('Unsupported native engine for AAudio label patch')
    result = engine[:OFFSET] + AFTER + engine[OFFSET + len(BEFORE):]
    if len(result) != len(engine) or hashlib.sha256(result).hexdigest() != PATCHED_SHA256:
        raise ValueError('AAudio native label patch verification failed')
    return result
