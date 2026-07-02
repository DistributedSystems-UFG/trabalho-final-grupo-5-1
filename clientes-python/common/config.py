from __future__ import annotations

import os


def env(nome: str, padrao: str) -> str:
    valor = os.getenv(nome)
    return valor if valor else padrao


KAFKA_BOOTSTRAP_SERVERS = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
ESTOQUE_PRIMARIO = env("ESTOQUE_PRIMARIO", "localhost:50051")
ESTOQUE_REPLICA = env("ESTOQUE_REPLICA", "localhost:50054")
CARDAPIO_ICE_PROXY = env("CARDAPIO_ICE_PROXY", "Cardapio:tcp -h localhost -p 10000")

TOPICO_PEDIDOS_CONFIRMADOS = env("TOPICO_PEDIDOS_CONFIRMADOS", "pedidos.confirmados")
TOPICO_PEDIDOS_PRODUCAO = env("TOPICO_PEDIDOS_PRODUCAO", "pedidos.producao")
TOPICO_RESULTADO_PRODUCAO = env("TOPICO_RESULTADO_PRODUCAO", "pedidos.producao.resultado")
TOPICO_VENDAS = env("TOPICO_VENDAS_CONCRETIZADAS", "vendas.concretizadas")
TOPICO_ALERTAS = env("TOPICO_ALERTAS_ESTOQUE", "alertas.estoque")
TOPICO_SNAPSHOTS = env("TOPICO_SNAPSHOTS_ESTOQUE", "estoque.snapshots")
TOPICO_CONSUMO = env("TOPICO_ITENS_CONSUMIDOS", "itens.consumidos")

API_KEY_SISTEMA = env("API_KEY_SISTEMA", "")
API_KEY_COMPRAS = env("API_KEY_COMPRAS", "")
API_KEY_REPLICADOR = env("API_KEY_REPLICADOR", "")
