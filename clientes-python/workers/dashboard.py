from __future__ import annotations

from common.config import KAFKA_BOOTSTRAP_SERVERS, TOPICO_ALERTAS, TOPICO_CONSUMO, TOPICO_VENDAS
from common.kafka_json import consumidor


def main() -> None:
    consumer = consumidor(
        KAFKA_BOOTSTRAP_SERVERS,
        [TOPICO_ALERTAS, TOPICO_CONSUMO, TOPICO_VENDAS],
        "dashboard-operacional",
        "dashboard-console",
    )
    print("=== Dashboard operacional ===")
    try:
        for registro in consumer:
            print(f"[{registro.topic}] chave={registro.key}: {registro.value}")
            consumer.commit()
    finally:
        consumer.close()


if __name__ == "__main__":
    main()
