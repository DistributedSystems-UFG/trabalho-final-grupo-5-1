from __future__ import annotations

import os
import time

from common.config import API_KEY_COMPRAS, ESTOQUE_PRIMARIO, KAFKA_BOOTSTRAP_SERVERS, TOPICO_ALERTAS
from common.grpc_estoque import EstoqueClient
from common.kafka_json import consumidor


def main() -> None:
    quantidade = int(os.getenv("QUANTIDADE_REPOSICAO", "100"))
    consumer = consumidor(
        KAFKA_BOOTSTRAP_SERVERS,
        [TOPICO_ALERTAS],
        "comprador-insumos",
        "comprador-1",
    )
    estoque = EstoqueClient(ESTOQUE_PRIMARIO, API_KEY_COMPRAS)
    print("Comprador aguardando alertas de insumos.")
    try:
        for registro in consumer:
            evento = registro.value
            if evento.get("tipo") != "INSUMO_BAIXO":
                consumer.commit()
                continue
            item = str(evento.get("item", ""))
            operacao_id = f"compra:{evento.get('alerta_id')}"
            try:
                resposta = estoque.repor({item: quantidade}, operacao_id)
                print(f"Reposição de {item}: {resposta.mensagem}")
                consumer.commit()
            except Exception as exc:
                print(f"Falha na reposição; offset não confirmado: {exc}")
                time.sleep(2)
    finally:
        estoque.close()
        consumer.close()


if __name__ == "__main__":
    main()
