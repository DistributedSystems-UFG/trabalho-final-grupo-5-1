package br.ufg.marmitaria.estoques.modelo;

import br.ufg.marmitaria.estoques.dominio.ChaveMarmita;

import java.util.Objects;

public record ItemPedido(ChaveMarmita chave, int quantidade) {
    public ItemPedido {
        Objects.requireNonNull(chave, "chave");
        if (quantidade <= 0) {
            throw new IllegalArgumentException("A quantidade deve ser positiva.");
        }
    }
}
