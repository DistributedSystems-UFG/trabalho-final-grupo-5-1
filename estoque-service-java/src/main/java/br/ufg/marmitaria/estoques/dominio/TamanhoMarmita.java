package br.ufg.marmitaria.estoques.dominio;

public enum TamanhoMarmita {
    P(1, "EMBALAGEM_P"),
    M(2, "EMBALAGEM_M"),
    G(3, "EMBALAGEM_G");

    private final int porcoesBase;
    private final String embalagem;

    TamanhoMarmita(int porcoesBase, String embalagem) {
        this.porcoesBase = porcoesBase;
        this.embalagem = embalagem;
    }

    public int porcoesBase() {
        return porcoesBase;
    }

    public String embalagem() {
        return embalagem;
    }
}
