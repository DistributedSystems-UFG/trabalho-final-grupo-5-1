package br.ufg.marmitaria.cardapio.servico;

import Marmitaria.Cardapio;
import Marmitaria.RespostaPedido;
import Marmitaria.StatusPedido;
import br.ufg.marmitaria.cardapio.estoque.EstoqueGrpcClient;
import br.ufg.marmitaria.cardapio.eventos.EventoPedidoPublisher;
import br.ufg.marmitaria.cardapio.modelo.ItemPedidoInterno;
import br.ufg.marmitaria.cardapio.modelo.PedidoInterno;
import br.ufg.marmitaria.cardapio.modelo.PedidoStore;
import com.zeroc.Ice.Current;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Servant Ice. Não mantém estado mutável próprio: dependências são finais e
 * todas as variáveis de uma chamada são locais/confinadas à thread corrente.
 */
public final class CardapioI implements Cardapio {
    private final EstoqueGrpcClient estoque;
    private final PedidoStore store;
    private final EventoPedidoPublisher eventos;

    public CardapioI(EstoqueGrpcClient estoque, PedidoStore store, EventoPedidoPublisher eventos) {
        this.estoque = estoque;
        this.store = store;
        this.eventos = eventos;
    }

    @Override
    public RespostaPedido fazerPedido(Marmitaria.ItemPedido[] itens, String clienteId, Current current) {
        try {
            List<ItemPedidoInterno> itensInternos = validarEConverter(itens);
            String pedidoId = UUID.randomUUID().toString();
            double valor = calcularValor(itensInternos);
            long agora = System.currentTimeMillis();

            PedidoInterno pedido = new PedidoInterno(
                    pedidoId,
                    clienteId == null || clienteId.isBlank() ? "anonimo" : clienteId,
                    "RECUSADO",
                    "Pedido em validação.",
                    0,
                    valor,
                    itensInternos,
                    List.of(),
                    agora,
                    agora);
            store.criar(pedido);

            EstoqueGrpcClient.ReservaResultado reserva = estoque.reservar(pedidoId, itensInternos);
            if (reserva.sucesso()) {
                PedidoInterno confirmado = store.atualizar(pedidoId, p -> p.comStatus(
                        "CONFIRMADO",
                        "Pedido confirmado; estoque reservado atomicamente.",
                        2));
                eventos.publicarPedidoConfirmado(confirmado);
                return paraIce(confirmado);
            }

            EstoqueGrpcClient.PlanoResultado plano = estoque.planejar(itensInternos);
            if (plano.podeProduzir()) {
                PedidoInterno aguardando = store.atualizar(pedidoId, p -> p.comOferta(
                        "AGUARDANDOCONFIRMACAOPRODUCAO",
                        "Não há marmitas prontas suficientes. Podemos produzir; deseja aguardar cerca de 10 minutos?",
                        plano.producaoNecessaria(),
                        10));
                return paraIce(aguardando);
            }

            if (!plano.ofertaParcial().isEmpty()) {
                PedidoInterno parcial = store.atualizar(pedidoId, p -> p.comOferta(
                        "AGUARDANDOCONFIRMACAOPARCIAL",
                        "Não há insumos para completar o pedido. Você aceita a quantidade disponível?",
                        plano.ofertaParcial(),
                        0));
                return paraIce(parcial);
            }

            if (plano.deveFechar()) {
                estoque.fecharLoja(pedidoId, "Inventário insuficiente para qualquer atendimento.");
            }
            PedidoInterno recusado = store.atualizar(pedidoId, p -> p.comStatus(
                    plano.deveFechar() ? "FECHADO" : "RECUSADO",
                    plano.deveFechar()
                            ? "Obrigado pelo contato, mas encerramos por hoje."
                            : plano.mensagem(),
                    0));
            return paraIce(recusado);
        } catch (Exception e) {
            return erro("Não foi possível registrar o pedido: " + mensagem(e));
        }
    }

    @Override
    public RespostaPedido responderProposta(String pedidoId, boolean aceitar, Current current) {
        try {
            PedidoInterno pedido = store.buscar(pedidoId)
                    .orElseThrow(() -> new IllegalArgumentException("Pedido não encontrado."));

            if (!aceitar) {
                return paraIce(store.atualizar(pedidoId, p -> p.comStatus(
                        "CANCELADO",
                        "Proposta recusada pelo cliente.",
                        0)));
            }

            if ("AGUARDANDOCONFIRMACAOPRODUCAO".equals(pedido.status())) {
                PedidoInterno emProducao = store.atualizar(pedidoId, p -> p.comStatus(
                        "EMPRODUCAO",
                        "Pedido enviado para a fila de produção.",
                        10));
                eventos.publicarPedidoParaProducao(emProducao);
                return paraIce(emProducao);
            }

            if ("AGUARDANDOCONFIRMACAOPARCIAL".equals(pedido.status())) {
                EstoqueGrpcClient.ReservaResultado reserva = estoque.reservar(
                        pedidoId + ":parcial",
                        pedido.itensOferecidos());
                if (!reserva.sucesso()) {
                    return paraIce(store.atualizar(pedidoId, p -> p.comStatus(
                            "RECUSADO",
                            "A oferta parcial deixou de estar disponível devido a outro pedido concorrente.",
                            0)));
                }
                PedidoInterno confirmado = store.atualizar(
                        pedidoId,
                        p -> p.comOfertaConfirmada(calcularValor(p.itensOferecidos())));
                eventos.publicarPedidoConfirmado(confirmado);
                return paraIce(confirmado);
            }

            return paraIce(pedido);
        } catch (Exception e) {
            return erro("Não foi possível responder à proposta: " + mensagem(e));
        }
    }

    @Override
    public RespostaPedido consultarPedido(String pedidoId, Current current) {
        try {
            return store.buscar(pedidoId).map(CardapioI::paraIce)
                    .orElseGet(() -> erro("Pedido não encontrado."));
        } catch (IOException e) {
            return erro("Falha ao consultar pedido: " + mensagem(e));
        }
    }

    @Override
    public String consultarDisponibilidade(Current current) {
        return estoque.consultarDisponibilidadeJson();
    }

    private static List<ItemPedidoInterno> validarEConverter(Marmitaria.ItemPedido[] itens) {
        if (itens == null || itens.length == 0) {
            throw new IllegalArgumentException("O pedido deve possuir ao menos um item.");
        }
        List<ItemPedidoInterno> resultado = new ArrayList<>();
        for (Marmitaria.ItemPedido item : itens) {
            if (item == null || item.quantidade <= 0) {
                throw new IllegalArgumentException("Item de pedido inválido.");
            }
            resultado.add(new ItemPedidoInterno(
                    item.tamanho.name(),
                    item.proteina.name(),
                    item.quantidade));
        }
        return List.copyOf(resultado);
    }

    private static double calcularValor(List<ItemPedidoInterno> itens) {
        double p = envDouble("PRECO_MARMITA_P", 20.0);
        double m = envDouble("PRECO_MARMITA_M", 25.0);
        double g = envDouble("PRECO_MARMITA_G", 30.0);
        double total = 0;
        for (ItemPedidoInterno item : itens) {
            double preco = switch (item.tamanho().toUpperCase(Locale.ROOT)) {
                case "P" -> p;
                case "M" -> m;
                case "G" -> g;
                default -> throw new IllegalArgumentException("Tamanho inválido: " + item.tamanho());
            };
            total += preco * item.quantidade();
        }
        return total;
    }

    private static RespostaPedido paraIce(PedidoInterno pedido) {
        return new RespostaPedido(
                pedido.pedidoId(),
                StatusPedido.valueOf(pedido.status()),
                pedido.mensagem(),
                pedido.tempoEstimadoSegundos(),
                pedido.valorTotal(),
                paraIce(pedido.itensSolicitados()),
                paraIce(pedido.itensOferecidos()));
    }

    private static Marmitaria.ItemPedido[] paraIce(List<ItemPedidoInterno> itens) {
        Marmitaria.ItemPedido[] resultado = new Marmitaria.ItemPedido[itens.size()];
        for (int i = 0; i < itens.size(); i++) {
            ItemPedidoInterno item = itens.get(i);
            resultado[i] = new Marmitaria.ItemPedido(
                    Marmitaria.TamanhoMarmita.valueOf(item.tamanho()),
                    Marmitaria.TipoProteina.valueOf(item.proteina()),
                    item.quantidade());
        }
        return resultado;
    }

    private static RespostaPedido erro(String mensagem) {
        return new RespostaPedido(
                "",
                StatusPedido.RECUSADO,
                mensagem,
                0,
                0.0,
                new Marmitaria.ItemPedido[0],
                new Marmitaria.ItemPedido[0]);
    }

    private static String mensagem(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static double envDouble(String nome, double padrao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? padrao : Double.parseDouble(valor);
    }
}
