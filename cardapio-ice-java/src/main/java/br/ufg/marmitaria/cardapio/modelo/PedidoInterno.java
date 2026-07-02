package br.ufg.marmitaria.cardapio.modelo;

import java.util.List;

public record PedidoInterno(
        String pedidoId,
        String clienteId,
        String status,
        String mensagem,
        int tempoEstimadoSegundos,
        double valorTotal,
        List<ItemPedidoInterno> itensSolicitados,
        List<ItemPedidoInterno> itensOferecidos,
        long criadoEmEpochMs,
        long atualizadoEmEpochMs) {

    public PedidoInterno {
        itensSolicitados = List.copyOf(itensSolicitados);
        itensOferecidos = List.copyOf(itensOferecidos);
    }

    public PedidoInterno comStatus(String novoStatus, String novaMensagem, int tempo) {
        return new PedidoInterno(
                pedidoId,
                clienteId,
                novoStatus,
                novaMensagem,
                tempo,
                valorTotal,
                itensSolicitados,
                itensOferecidos,
                criadoEmEpochMs,
                System.currentTimeMillis());
    }

    public PedidoInterno comOfertaConfirmada(double novoValor) {
        return new PedidoInterno(
                pedidoId,
                clienteId,
                "CONFIRMADO",
                "Oferta parcial aceita e reservada.",
                2,
                novoValor,
                itensSolicitados,
                itensOferecidos,
                criadoEmEpochMs,
                System.currentTimeMillis());
    }

    public PedidoInterno comOferta(String novoStatus, String novaMensagem, List<ItemPedidoInterno> oferta, int tempo) {
        return new PedidoInterno(
                pedidoId,
                clienteId,
                novoStatus,
                novaMensagem,
                tempo,
                valorTotal,
                itensSolicitados,
                oferta,
                criadoEmEpochMs,
                System.currentTimeMillis());
    }
}
