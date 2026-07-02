package br.ufg.marmitaria.estoques.modelo;

public record AlertaEstoque(
        String alertaId,
        String tipo,
        String item,
        int quantidadeAtual,
        int limite,
        long versaoInventario,
        String mensagem) {
}
