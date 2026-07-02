from __future__ import annotations

import json
from typing import Any
from kafka import KafkaConsumer, KafkaProducer


def produtor(bootstrap_servers: str, client_id: str) -> KafkaProducer:
    return KafkaProducer(
        bootstrap_servers=bootstrap_servers,
        client_id=client_id,
        acks="all",
        key_serializer=lambda value: value.encode("utf-8") if value else None,
        value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
    )


def consumidor(
    bootstrap_servers: str,
    topicos: list[str],
    group_id: str,
    client_id: str,
) -> KafkaConsumer:
    return KafkaConsumer(
        *topicos,
        bootstrap_servers=bootstrap_servers,
        group_id=group_id,
        client_id=client_id,
        enable_auto_commit=False,
        auto_offset_reset="earliest",
        key_deserializer=lambda value: value.decode("utf-8") if value else None,
        value_deserializer=lambda value: json.loads(value.decode("utf-8")),
    )


def publicar(producer: KafkaProducer, topico: str, chave: str, evento: dict[str, Any]) -> None:
    future = producer.send(topico, key=chave, value=evento)
    future.get(timeout=10)
