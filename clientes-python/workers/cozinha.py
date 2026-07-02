from __future__ import annotations

import os
import time
from datetime import datetime, timezone

from common.config import (
    API_KEY_SISTEMA, ESTOQUE_PRIMARIO,
    KAFKA_BOOTSTRAP_SERVERS,
    TOPICO_ALERTAS,
    TOPICO_PEDIDOS_CONFIRMADOS,
    TOPICO_PEDIDOS_PRODUCAO,
    TOPICO_RESULTADO_PRODUCAO,
)
from common.grpc_estoque import EstoqueClient, Item, itens_evento
from common.kafka_json import consumidor, produtor, publicar


def _item_alerta(evento: dict) -> Item | None:
    if evento.get("tipo") != "MARMITA_BAIXA":
        return None
    partes = str(evento.get("item", "")).split("_", 1)
    if len(partes) != 2:
        return None
    return Item(partes[0], partes[1], 10)


def main() -> None:
    atraso = float(os.getenv("TEMPO_PRODUCAO_SEGUNDOS", "10"))
    consumer = consumidor(
        KAFKA_BOOTSTRAP_SERVERS,
        [TOPICO_PEDIDOS_PRODUCAO, TOPICO_ALERTAS],
        "cozinha-producao",
        "cozinha-1",
    )
    producer = produtor(KAFKA_BOOTSTRAP_SERVERS, "cozinha-producer")
    estoque = EstoqueClient(ESTOQUE_PRIMARIO, API_KEY_SISTEMA)
    print("Cozinha aguardando pedidos e alertas de estoque baixo.")
    try:
        for registro in consumer:
            evento = registro.value
            try:
                if registro.topic == TOPICO_PEDIDOS_PRODUCAO:
                    pedido_id = evento["pedido_id"]
                    itens = itens_evento(evento)
                    time.sleep(atraso)
                    resposta = estoque.produzir_e_reservar(
                        f"producao-pedido:{pedido_id}", pedido_id, itens, lote=10
                    )
                    resultado = {
                        "evento_id": f"PRODUCAO_RESULTADO:{pedido_id}",
                        "tipo": "PRODUCAO_PEDIDO_CONCLUIDA",
                        "pedido_id": pedido_id,
                        "sucesso": resposta.sucesso,
                        "mensagem": resposta.mensagem,
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                    }
                    publicar(producer, TOPICO_RESULTADO_PRODUCAO, pedido_id, resultado)
                    if resposta.sucesso:
                        confirmado = dict(evento)
                        confirmado["tipo"] = "PEDIDO_CONFIRMADO"
                        confirmado["evento_id"] = f"PEDIDO_CONFIRMADO:{pedido_id}:apos-producao"
                        publicar(producer, TOPICO_PEDIDOS_CONFIRMADOS, pedido_id, confirmado)
                    print(f"Produção do pedido {pedido_id}: {resposta.mensagem}")
                else:
                    item = _item_alerta(evento)
                    if item is None:
                        consumer.commit()
                        continue
                    operacao_id = f"producao-alerta:{evento.get('alerta_id')}"
                    resposta = estoque.produzir(operacao_id, item)
                    print(f"Produção preventiva {item.tamanho}/{item.proteina}: {resposta.mensagem}")
                consumer.commit()
            except Exception as exc:
                print(f"Falha na cozinha; offset não confirmado: {exc}")
                time.sleep(2)
    finally:
        estoque.close()
        consumer.close()
        producer.flush()
        producer.close()


if __name__ == "__main__":
    main()
