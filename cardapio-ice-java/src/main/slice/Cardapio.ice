module Marmitaria
{
    enum TamanhoMarmita { P, M, G };
    enum TipoProteina { BOVINA, SUINA, FRANGO };

    enum StatusPedido
    {
        CONFIRMADO,
        AGUARDANDOCONFIRMACAOPRODUCAO,
        AGUARDANDOCONFIRMACAOPARCIAL,
        EMPRODUCAO,
        EMPROCESSAMENTO,
        CONCLUIDO,
        CANCELADO,
        RECUSADO,
        FECHADO
    };

    struct ItemPedido
    {
        TamanhoMarmita tamanho;
        TipoProteina proteina;
        int quantidade;
    };

    sequence<ItemPedido> ItensPedido;

    struct RespostaPedido
    {
        string pedidoId;
        StatusPedido status;
        string mensagem;
        int tempoEstimadoSegundos;
        double valorTotal;
        ItensPedido itensSolicitados;
        ItensPedido itensOferecidos;
    };

    interface Cardapio
    {
        RespostaPedido fazerPedido(ItensPedido itens, string clienteId);
        RespostaPedido responderProposta(string pedidoId, bool aceitar);
        RespostaPedido consultarPedido(string pedidoId);
        string consultarDisponibilidade();
    };
};
