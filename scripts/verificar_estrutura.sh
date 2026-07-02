#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 -m compileall -q clientes-python
python3 -m json.tool estoque-service-java/dados-principal/inventario.json >/dev/null
python3 -m json.tool estoque-service-java/dados-replica/inventario.json >/dev/null
cmp -s estoque-service-java/src/main/proto/SistemaDeEstoques.proto        cardapio-ice-java/src/main/proto/SistemaDeEstoques.proto

echo "Sintaxe Python, JSON e cópias do contrato gRPC: OK"
