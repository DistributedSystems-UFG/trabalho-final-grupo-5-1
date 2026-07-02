package br.ufg.marmitaria.estoques.dominio;

import java.util.Objects;

public record ChaveMarmita(TamanhoMarmita tamanho, TipoProteina proteina) {
    public ChaveMarmita {
        Objects.requireNonNull(tamanho, "tamanho");
        Objects.requireNonNull(proteina, "proteina");
    }

    public String codigo() {
        return tamanho.name() + "_" + proteina.name();
    }

    public static ChaveMarmita deCodigo(String codigo) {
        String[] partes = codigo.split("_", 2);
        if (partes.length != 2) {
            throw new IllegalArgumentException("Código de marmita inválido: " + codigo);
        }
        return new ChaveMarmita(
                TamanhoMarmita.valueOf(partes[0]),
                TipoProteina.valueOf(partes[1]));
    }
}
