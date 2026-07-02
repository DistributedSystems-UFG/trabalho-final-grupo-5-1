package br.ufg.marmitaria.estoques.eventos;

import br.ufg.marmitaria.estoques.repositorio.InventarioArquivoRepository;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Republica snapshots periodicamente. Isso reduz a janela de indisponibilidade
 * caso o Kafka esteja temporariamente fora do ar durante uma mutação.
 */
public final class VerificadorPeriodicoEstoque implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(VerificadorPeriodicoEstoque.class.getName());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "snapshot-periodico-estoque");
        t.setDaemon(true);
        return t;
    });

    public VerificadorPeriodicoEstoque(
            InventarioArquivoRepository repository,
            EventoEstoquePublisher publisher,
            long intervaloSegundos) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                publisher.publicarSnapshotPeriodico(repository.consultar());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Falha ao ler inventário para snapshot periódico", e);
            }
        }, intervaloSegundos, intervaloSegundos, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
