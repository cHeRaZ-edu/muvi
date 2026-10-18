#!/bin/sh
# Baja muve-android.aar (la libreria del SDK de muve drive) de un release y revisa su checksum.
#   sh scripts/actualizar-muve.sh 0.2.0
# Despues: commit de app/libs/ (el .aar, su .sha256 y MUVE_VERSION).
set -eu
V="${1:?uso: sh scripts/actualizar-muve.sh VERSION (ej. 0.2.0)}"
V="${V#v}"
BASE="https://github.com/cHeRaZ-edu/muve/releases/download/v$V"
DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/muve-android.aar" "$BASE/muve-android.aar"
curl -fsSL -o "$tmp/checksums.txt" "$BASE/checksums.txt"
want="$(awk '$2 == "muve-android.aar" { print $1 }' "$tmp/checksums.txt")"
got="$(sha256sum "$tmp/muve-android.aar" | cut -d' ' -f1)"
[ -n "$want" ] && [ "$want" = "$got" ] || { echo "el checksum no coincide; no se cambio nada" >&2; exit 1; }
mkdir -p "$DIR"
mv "$tmp/muve-android.aar" "$DIR/muve-android.aar"
echo "$got  muve-android.aar" > "$DIR/muve-android.aar.sha256"
echo "$V" > "$DIR/MUVE_VERSION"
echo "muve-android.aar v$V listo en app/libs/"
