from __future__ import annotations

import pathlib
import sys
from grpc_tools import protoc

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROTO = ROOT / "estoque-service-java" / "src" / "main" / "proto" / "SistemaDeEstoques.proto"
OUT = pathlib.Path(__file__).resolve().parent / "gerado_grpc"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "__init__.py").touch()
    resultado = protoc.main([
        "grpc_tools.protoc",
        f"-I{PROTO.parent}",
        f"--python_out={OUT}",
        f"--pyi_out={OUT}",
        f"--grpc_python_out={OUT}",
        str(PROTO),
    ])
    if resultado != 0:
        raise SystemExit(resultado)
    print(f"Stubs gRPC gerados em {OUT}")


if __name__ == "__main__":
    main()
