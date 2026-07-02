package br.ufg.marmitaria.estoques.dominio;

public enum TipoProteina {
    BOVINA("CARNE_BOVINA"),
    SUINA("CARNE_SUINA"),
    FRANGO("CARNE_FRANGO");

    private final String insumo;

    TipoProteina(String insumo) {
        this.insumo = insumo;
    }

    public String insumo() {
        return insumo;
    }
}
