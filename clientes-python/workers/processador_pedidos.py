from __future__ import annotations

import os
import time
from datetime import datetime, timezone

from common.config import KAFKA_BOOTSTRAP_SERVERS, TOPICO_PEDIDOS_CONFIRMADOS, TOPICO_VENDAS
from common.kafka_json import consumidor, produtor, publicar


def main() -> None:
    worker_id = os.getenv("WORKER_ID", "worker-1")
    atraso = float(os.getenv("TEMPO_PROCESSAMENTO_PEDIDO_SEGUNDOS", "2"))
    consumer = consumidor(
        KAFKA_BOOTSTRAP_SERVERS,
        [TOPICO_PEDIDOS_CONFIRMADOS],
        "workers-processamento-pedidos",
        worker_id,
    )
    producer = produtor(KAFKA_BOOTSTRAP_SERVERS, worker_id + "-producer")
    print(f"{worker_id} consumindo {TOPICO_PEDIDOS_CONFIRMADOS}")
    try:
        for registro in consumer:
            evento = registro.value
            pedido_id = evento["pedido_id"]
            time.sleep(atraso)
            venda = {
                "evento_id": f"VENDA:{pedido_id}",
                "tipo": "VENDA_CONCRETIZADA",
                "pedido_id": pedido_id,
                "cliente_id": evento.get("cliente_id"),
                "itens": evento.get("itens", []),
                "valor_total": evento.get("valor_total", 0),
                "worker_id": worker_id,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
            publicar(producer, TOPICO_VENDAS, pedido_id, venda)
            consumer.commit()
            print(f"[{worker_id}] venda concretizada: {pedido_id}")
    finally:
        consumer.close()
        producer.flush()
        producer.close()


if __name__ == "__main__":
    main()
