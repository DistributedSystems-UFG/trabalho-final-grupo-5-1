#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/clientes-python/gerado_ice"
mkdir -p "$OUT"
slice2py --output-dir "$OUT" "$ROOT/cardapio-ice-java/src/main/slice/Cardapio.ice"
touch "$OUT/__init__.py"
echo "Stubs Ice gerados em $OUT"
