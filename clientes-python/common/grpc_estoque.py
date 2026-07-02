from __future__ import annotations

import pathlib
import sys
import uuid
from dataclasses import dataclass
from typing import Iterable

import grpc

GERADO = pathlib.Path(__file__).resolve().parents[1] / "gerado_grpc"
if str(GERADO) not in sys.path:
    sys.path.insert(0, str(GERADO))

import SistemaDeEstoques_pb2 as pb2  # type: ignore  # noqa: E402
import SistemaDeEstoques_pb2_grpc as pb2_grpc  # type: ignore  # noqa: E402


@dataclass(frozen=True)
class Item:
    tamanho: str
    proteina: str
    quantidade: int


def _enum_tamanho(valor: str) -> int:
    return pb2.TipoMarmita.Value(f"MARMITA_{valor.upper()}")


def _enum_proteina(valor: str) -> int:
    return pb2.TipoProteina.Value(valor.upper())


def item_proto(item: Item):
    return pb2.ItemMarmita(
        tamanho=_enum_tamanho(item.tamanho),
        proteina=_enum_proteina(item.proteina),
        quantidade=item.quantidade,
    )


class EstoqueClient:
    def __init__(self, endereco: str, api_key: str = ""):
        self._channel = grpc.insecure_channel(endereco)
        self._stub = pb2_grpc.SistemaDeEstoquesStub(self._channel)
        self._metadata = (("x-api-key", api_key),) if api_key else None

    def close(self) -> None:
        self._channel.close()

    def consultar(self):
        return self._stub.ConsultarEstoqueGeral(
            pb2.ConsultaEstoqueRequest(), timeout=5, metadata=self._metadata)

    def produzir(self, operacao_id: str, item: Item):
        return self._stub.ProduzirMarmitas(
            pb2.ProduzirMarmitasRequest(operacao_id=operacao_id, item=item_proto(item)),
            timeout=10, metadata=self._metadata,
        )

    def produzir_e_reservar(self, operacao_id: str, pedido_id: str, itens: Iterable[Item], lote: int = 10):
        return self._stub.ProduzirEReservar(
            pb2.ProduzirEReservarRequest(
                operacao_id=operacao_id,
                pedido_id=pedido_id,
                itens_pedido=[item_proto(i) for i in itens],
                tamanho_lote=lote,
            ),
            timeout=15, metadata=self._metadata,
        )

    def repor(self, reposicoes: dict[str, int], operacao_id: str | None = None):
        itens = [
            pb2.QuantidadeInsumo(tipo=pb2.TipoInsumo.Value(nome), quantidade=qtd)
            for nome, qtd in reposicoes.items()
        ]
        return self._stub.ReporInsumos(
            pb2.ReporInsumosRequest(
                operacao_id=operacao_id or f"reposicao:{uuid.uuid4()}",
                itens=itens,
            ),
            timeout=10, metadata=self._metadata,
        )

    def reservar(self, operacao_id: str, pedido_id: str, itens: Iterable[Item]):
        return self._stub.ReservarMarmitas(
            pb2.ReservaMarmitasRequest(
                operacao_id=operacao_id,
                pedido_id=pedido_id,
                itens=[item_proto(i) for i in itens],
            ),
            timeout=10, metadata=self._metadata,
        )

    def aplicar_snapshot(self, operacao_id: str, snapshot):
        return self._stub.AplicarSnapshotReplica(
            pb2.AplicarSnapshotReplicaRequest(
                operacao_id=operacao_id,
                snapshot=snapshot,
            ),
            timeout=10, metadata=self._metadata,
        )


def itens_evento(evento: dict) -> list[Item]:
    resultado: list[Item] = []
    for item in evento.get("itens", []):
        resultado.append(Item(
            tamanho=str(item["tamanho"]).upper(),
            proteina=str(item["proteina"]).upper(),
            quantidade=int(item["quantidade"]),
        ))
    return resultado
