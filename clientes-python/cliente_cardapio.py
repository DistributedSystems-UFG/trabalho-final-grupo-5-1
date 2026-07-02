from __future__ import annotations

import argparse
import pathlib
import sys

import Ice

from common.config import CARDAPIO_ICE_PROXY

GERADO = pathlib.Path(__file__).resolve().parent / "gerado_ice"
if str(GERADO) not in sys.path:
    sys.path.insert(0, str(GERADO))

import Marmitaria  # type: ignore  # noqa: E402


def mostrar(resposta) -> None:
    print(f"Pedido: {resposta.pedidoId or '-'}")
    print(f"Status: {resposta.status}")
    print(f"Mensagem: {resposta.mensagem}")
    print(f"Valor: R$ {resposta.valorTotal:.2f}")
    if resposta.tempoEstimadoSegundos:
        print(f"Tempo estimado da demonstração: {resposta.tempoEstimadoSegundos}s")
    if resposta.itensOferecidos:
        print("Oferta disponível:")
        for i in resposta.itensOferecidos:
            print(f"  {i.quantidade}x {i.tamanho}/{i.proteina}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Cliente Python do cardápio Ice")
    parser.add_argument("--proxy", default=CARDAPIO_ICE_PROXY)
    parser.add_argument("--cliente", default="cliente-python")
    args = parser.parse_args()

    with Ice.initialize(sys.argv) as communicator:
        base = communicator.stringToProxy(args.proxy)
        cardapio = Marmitaria.CardapioPrx.checkedCast(base)
        if not cardapio:
            raise RuntimeError("Não foi possível obter o proxy do cardápio.")

        print("=== Cardápio digital ===")
        print(cardapio.consultarDisponibilidade())
        tamanho = input("Tamanho (P/M/G): ").strip().upper()
        proteina = input("Proteína (BOVINA/SUINA/FRANGO): ").strip().upper()
        quantidade = int(input("Quantidade: ").strip())
        item = Marmitaria.ItemPedido(
            getattr(Marmitaria.TamanhoMarmita, tamanho),
            getattr(Marmitaria.TipoProteina, proteina),
            quantidade,
        )
        resposta = cardapio.fazerPedido([item], args.cliente)
        mostrar(resposta)

        if resposta.status in (
            Marmitaria.StatusPedido.AGUARDANDOCONFIRMACAOPRODUCAO,
            Marmitaria.StatusPedido.AGUARDANDOCONFIRMACAOPARCIAL,
        ):
            aceitar = input("Aceitar proposta? (s/n): ").strip().lower() == "s"
            resposta = cardapio.responderProposta(resposta.pedidoId, aceitar)
            mostrar(resposta)

        if resposta.pedidoId:
            input("Pressione Enter para consultar o estado atualizado...")
            mostrar(cardapio.consultarPedido(resposta.pedidoId))


if __name__ == "__main__":
    main()
