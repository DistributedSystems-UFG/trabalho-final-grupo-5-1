package br.ufg.marmitaria.estoques.eventos;

import br.ufg.marmitaria.estoques.modelo.InventarioEstado;
import br.ufg.marmitaria.estoques.modelo.ResultadoOperacao;

public interface EventoEstoquePublisher extends AutoCloseable {
    void publicarResultado(String tipoEvento, String operacaoId, ResultadoOperacao resultado);
    void publicarSnapshotPeriodico(InventarioEstado snapshot);
    @Override
    void close();
}
