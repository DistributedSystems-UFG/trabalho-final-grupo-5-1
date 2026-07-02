from __future__ import annotations

import argparse
import concurrent.futures
import uuid

from common.config import API_KEY_SISTEMA
from common.grpc_estoque import EstoqueClient, Item


def executar(endereco: str, chamadas: int, quantidade: int) -> None:
    item = Item("P", "BOVINA", quantidade)

    def reservar(indice: int) -> bool:
        cliente = EstoqueClient(endereco, API_KEY_SISTEMA)
        try:
            resposta = cliente.reservar(
                f"teste-corrente:{uuid.uuid4()}",
                f"pedido-teste-{indice}",
                [item],
            )
            print(indice, resposta.sucesso, resposta.mensagem)
            return bool(resposta.sucesso)
        finally:
            cliente.close()

    with concurrent.futures.ThreadPoolExecutor(max_workers=chamadas) as executor:
        resultados = list(executor.map(reservar, range(chamadas)))
    print(f"Sucessos: {sum(resultados)}; recusas seguras: {len(resultados)-sum(resultados)}")
    print("O estoque final deve ser não negativo e o número de sucessos deve respeitar o saldo inicial.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endereco", default="localhost:50051")
    parser.add_argument("--chamadas", type=int, default=20)
    parser.add_argument("--quantidade", type=int, default=1)
    args = parser.parse_args()
    executar(args.endereco, args.chamadas, args.quantidade)


if __name__ == "__main__":
    main()
