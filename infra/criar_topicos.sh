#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
PARTICOES_PEDIDOS="${PARTICOES_PEDIDOS:-3}"
PARTICOES_EVENTOS="${PARTICOES_EVENTOS:-3}"
REPLICACAO="${KAFKA_REPLICATION_FACTOR:-1}"
KAFKA_TOPICS_BIN="${KAFKA_TOPICS_BIN:-kafka-topics.sh}"

criar() {
  local topico="$1" particoes="$2"
  "$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP"     --create --if-not-exists     --topic "$topico"     --partitions "$particoes"     --replication-factor "$REPLICACAO"
}

criar pedidos.confirmados "$PARTICOES_PEDIDOS"
criar pedidos.producao "$PARTICOES_PEDIDOS"
criar pedidos.producao.resultado "$PARTICOES_EVENTOS"
criar vendas.concretizadas "$PARTICOES_EVENTOS"
criar alertas.estoque "$PARTICOES_EVENTOS"
criar itens.consumidos "$PARTICOES_EVENTOS"
criar estoque.snapshots 1

echo "Tópicos criados em $BOOTSTRAP (replicação=$REPLICACAO)."
