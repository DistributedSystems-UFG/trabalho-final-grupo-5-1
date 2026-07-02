package br.ufg.marmitaria.cardapio.modelo;

public record ItemPedidoInterno(String tamanho, String proteina, int quantidade) {
    public ItemPedidoInterno {
        if (tamanho == null || tamanho.isBlank() || proteina == null || proteina.isBlank()) {
            throw new IllegalArgumentException("Tamanho e proteína são obrigatórios.");
        }
        if (quantidade <= 0) {
            throw new IllegalArgumentException("A quantidade deve ser positiva.");
        }
    }

    public String chave() {
        return tamanho + "_" + proteina;
    }
}
