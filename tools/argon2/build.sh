#!/bin/sh
# Baut argon2id.wasm aus der offiziellen Argon2-Referenzimplementierung (ref/, unverändert) und der dünnen Hülle glue.c.
# Builds argon2id.wasm from the official Argon2 reference implementation (ref/, unchanged) and the thin wrapper glue.c.
# Braucht clang mit wasm32-Ziel und wasm-ld. Gibt die SHA-256-Summe aus. / Needs clang with the wasm32 target and wasm-ld. Prints the SHA-256.
set -e
cd "$(dirname "$0")"
clang --target=wasm32 -O2 -ffreestanding -fno-builtin -nostdlib -fvisibility=hidden -DARGON2_NO_THREADS \
  -ffunction-sections -fdata-sections -Ilibc -Iref/include -Iref/src \
  -Wl,--no-entry -Wl,--export-dynamic -Wl,--export=__heap_base -Wl,-z,stack-size=65536 -Wl,--gc-sections \
  -Wl,--initial-memory=262144 -Wl,--strip-all \
  -o argon2id.wasm glue.c ref/src/argon2.c ref/src/core.c ref/src/ref.c ref/src/blake2/blake2b.c
ls -l argon2id.wasm
sha256sum argon2id.wasm
