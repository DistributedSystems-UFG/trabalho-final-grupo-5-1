from __future__ import annotations

import pathlib
import sys
import time

from google.protobuf.json_format import ParseDict

from common.config import API_KEY_REPLICADOR, ESTOQUE_REPLICA, KAFKA_BOOTSTRAP_SERVERS, TOPICO_SNAPSHOTS
from common.grpc_estoque import EstoqueClient
from common.kafka_json import consumidor

GERADO = pathlib.Path(__file__).resolve().parents[1] / "gerado_grpc"
if str(GERADO) not in sys.path:
    sys.path.insert(0, str(GERADO))
import SistemaDeEstoques_pb2 as pb2  # type: ignore  # noqa: E402


def _snapshot_proto(evento: dict):
    snapshot = evento["snapshot"]
    # O evento Java usa mapas; o protobuf usa listas. Fazemos a conversão explícita.
    marmitas = []
    for chave, qtd in snapshot["marmitas"].items():
        tamanho, proteina = chave.split("_", 1)
        marmitas.append({
            "tamanho": f"MARMITA_{tamanho}",
            "proteina": proteina,
            "quantidade": qtd,
        })
    insumos = [{"tipo": nome, "quantidade": qtd} for nome, qtd in snapshot["insumos"].items()]
    dados = {
        "versao": snapshot["versao"],
        "lojaAberta": snapshot["lojaAberta"],
        "marmitas": marmitas,
        "insumos": insumos,
        "atualizadoEmEpochMs": snapshot["atualizadoEmEpochMs"],
    }
    return ParseDict(dados, pb2.SnapshotInventario())


def main() -> None:
    consumer = consumidor(
        KAFKA_BOOTSTRAP_SERVERS,
        [TOPICO_SNAPSHOTS],
        "replicador-estoque",
        "replicador-1",
    )
    replica = EstoqueClient(ESTOQUE_REPLICA, API_KEY_REPLICADOR)
    print(f"Replicador aplicando snapshots em {ESTOQUE_REPLICA}")
    try:
        for registro in consumer:
            evento = registro.value
            try:
                snapshot = _snapshot_proto(evento)
                resposta = replica.aplicar_snapshot(
                    f"replica:{snapshot.versao}", snapshot
                )
                print(f"Snapshot v{snapshot.versao}: {resposta.mensagem}")
                consumer.commit()
            except Exception as exc:
                print(f"Falha na replicação; offset não confirmado: {exc}")
                time.sleep(2)
    finally:
        replica.close()
        consumer.close()


if __name__ == "__main__":
    main()
