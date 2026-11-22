#!/bin/sh
# Vergleicht zwei APKs ohne die Signatur (META-INF). Gleich = die Inhalte stimmen Byte für Byte überein.
# Compares two APKs ignoring the signature (META-INF). Equal = the contents match byte for byte.
# Usage: tools/compare-apks.sh a.apk b.apk
set -eu
if [ "$#" -ne 2 ]; then echo "Usage: $0 a.apk b.apk" >&2; exit 2; fi
A=$(mktemp -d); B=$(mktemp -d)
trap 'rm -rf "$A" "$B"' EXIT
unzip -q "$1" -d "$A"
unzip -q "$2" -d "$B"
rm -rf "$A/META-INF" "$B/META-INF"
if diff -rq "$A" "$B"; then
  echo "OK: Inhalte gleich / contents identical (Signatur ausgenommen / signature excluded)"
else
  echo "UNTERSCHIEDE / DIFFERENCES found" >&2
  exit 1
fi
