#!/bin/sh
# Baut argon2id.wasm aus argon2id.c (clang mit wasm32-Ziel und wasm-ld). Gibt die SHA-256-Summe aus.
# Builds argon2id.wasm from argon2id.c (clang with the wasm32 target and wasm-ld). Prints the SHA-256.
set -e
cd "$(dirname "$0")"
clang --target=wasm32 -O2 -ffreestanding -fno-builtin -nostdlib -fvisibility=hidden \
  -Wl,--no-entry -Wl,--export-dynamic -Wl,--export=__heap_base -Wl,-z,stack-size=65536 \
  -Wl,--initial-memory=262144 -Wl,--strip-all \
  -o argon2id.wasm argon2id.c
ls -l argon2id.wasm
sha256sum argon2id.wasm
