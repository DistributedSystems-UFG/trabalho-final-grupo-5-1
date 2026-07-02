from __future__ import annotations

import argparse

from common.config import API_KEY_SISTEMA
from common.grpc_estoque import EstoqueClient, Item


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endereco", default="localhost:50051")
    args = parser.parse_args()
    cliente = EstoqueClient(args.endereco, API_KEY_SISTEMA)
    try:
        operacao = "teste-idempotente-fixo"
        itens = [Item("P", "FRANGO", 1)]
        primeira = cliente.reservar(operacao, "pedido-idempotente", itens)
        segunda = cliente.reservar(operacao, "pedido-idempotente", itens)
        print("Primeira:", primeira.sucesso, primeira.ja_processada, primeira.mensagem)
        print("Segunda:", segunda.sucesso, segunda.ja_processada, segunda.mensagem)
        assert segunda.ja_processada, "A segunda chamada deveria ser reconhecida como repetida."
    finally:
        cliente.close()


if __name__ == "__main__":
    main()
