#!/usr/bin/env python3
"""Vergleicht argon2id.wasm mit zwei unabhängigen Umsetzungen: python-cryptography und libargon2.
Compares argon2id.wasm with two independent implementations: python-cryptography and libargon2."""
import json, os, random, subprocess, ctypes, sys
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id

here = os.path.dirname(os.path.abspath(__file__))
lib = ctypes.CDLL("libargon2.so.1")
lib.argon2id_hash_raw.argtypes = [ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_char_p, ctypes.c_size_t,
                                  ctypes.c_char_p, ctypes.c_size_t, ctypes.c_char_p, ctypes.c_size_t]

def ref_crypto(c):
    return Argon2id(salt=bytes.fromhex(c["salt"]), length=c["len"], iterations=c["t"], lanes=c["p"], memory_cost=c["m"]).derive(bytes.fromhex(c["pw"])).hex()

def ref_lib(c):
    out = ctypes.create_string_buffer(c["len"]); pw = bytes.fromhex(c["pw"]); salt = bytes.fromhex(c["salt"])
    rc = lib.argon2id_hash_raw(c["t"], c["m"], c["p"], pw, len(pw), salt, len(salt), out, c["len"])
    assert rc == 0, rc
    return out.raw.hex()

rnd = random.Random(20261122)
cases = [
    # feste Fälle, darunter die Chiffre-Einstellung / fixed cases incl. the Chiffre setting
    dict(pw=("01" * 32), salt=("02" * 16), t=3, m=32, p=4, len=32),
    dict(pw=b"password".hex(), salt=b"somesalt".hex(), t=2, m=64, p=1, len=32),
    dict(pw=b"password".hex(), salt=b"somesalt".hex(), t=1, m=8, p=1, len=32),
    dict(pw=b"".hex(), salt=("aa" * 16), t=1, m=8, p=1, len=32),
    dict(pw=b"correct horse battery staple".hex(), salt=bytes(range(16)).hex(), t=3, m=65536, p=1, len=32),
    dict(pw=b"x".hex(), salt=("00" * 16), t=4, m=1000, p=3, len=64),
    dict(pw=b"x".hex(), salt=("00" * 16), t=1, m=64, p=2, len=4),
    dict(pw=b"x".hex(), salt=("00" * 16), t=1, m=64, p=2, len=64),
]
for _ in range(60):
    p = rnd.choice([1, 1, 2, 3, 4, 8])
    cases.append(dict(pw=os.urandom(rnd.randint(0, 100)).hex(), salt=os.urandom(rnd.randint(8, 40)).hex(),
                      t=rnd.randint(1, 4), m=rnd.randint(8 * p, 600), p=p, len=rnd.choice([4, 16, 32, 33, 48, 64])))
res = json.loads(subprocess.run(["node", os.path.join(here, "run.mjs")], input=json.dumps(cases), capture_output=True, text=True, check=True).stdout)
bad = 0
for c, r in zip(cases, res):
    a, b = ref_crypto(c), ref_lib(c)
    ok = (r == a == b)
    if not ok:
        bad += 1; print("ABWEICHUNG", {k: (v if k not in ("pw", "salt") else v[:16]) for k, v in c.items()}, r[:16], a[:16], b[:16])
print(f"{len(cases)} Fälle, {bad} Abweichungen" if bad else f"OK: {len(cases)} Fälle, wasm == cryptography == libargon2")
sys.exit(1 if bad else 0)
