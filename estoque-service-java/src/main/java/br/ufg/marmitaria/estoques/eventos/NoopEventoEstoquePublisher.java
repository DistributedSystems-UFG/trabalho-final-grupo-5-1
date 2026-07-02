package br.ufg.marmitaria.estoques.eventos;

import br.ufg.marmitaria.estoques.modelo.InventarioEstado;
import br.ufg.marmitaria.estoques.modelo.ResultadoOperacao;

public final class NoopEventoEstoquePublisher implements EventoEstoquePublisher {
    @Override
    public void publicarResultado(String tipoEvento, String operacaoId, ResultadoOperacao resultado) {
    }

    @Override
    public void publicarSnapshotPeriodico(InventarioEstado snapshot) {
    }

    @Override
    public void close() {
    }
}
