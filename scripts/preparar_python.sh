#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r clientes-python/requirements.txt
python clientes-python/gerar_stubs_grpc.py
bash clientes-python/gerar_stubs_ice.sh
