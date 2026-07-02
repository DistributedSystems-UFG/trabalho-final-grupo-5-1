package br.ufg.marmitaria.estoques.servidor;

import br.ufg.marmitaria.estoques.concorrencia.FabricaThreadsNomeadas;
import br.ufg.marmitaria.estoques.eventos.EventoEstoquePublisher;
import br.ufg.marmitaria.estoques.eventos.KafkaEventoEstoquePublisher;
import br.ufg.marmitaria.estoques.eventos.NoopEventoEstoquePublisher;
import br.ufg.marmitaria.estoques.eventos.VerificadorPeriodicoEstoque;
import br.ufg.marmitaria.estoques.repositorio.InventarioArquivoRepository;
import br.ufg.marmitaria.estoques.servico.SistemaDeEstoquesService;
import br.ufg.marmitaria.estoques.seguranca.AutorizacaoInterceptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ServidorEstoque {
    private ServidorEstoque() {
    }

    public static void main(String[] args) throws Exception {
        int porta = args.length > 0 ? Integer.parseInt(args[0]) : 50051;
        Path diretorio = Path.of(args.length > 1 ? args[1] : "dados-principal");
        boolean replica = args.length > 2 && args[2].equalsIgnoreCase("replica");
        int workers = args.length > 3 ? Integer.parseInt(args[3]) : (replica ? 8 : 16);

        InventarioArquivoRepository repository = new InventarioArquivoRepository(diretorio);
        ExecutorService executor = Executors.newFixedThreadPool(
                workers,
                new FabricaThreadsNomeadas(replica ? "estoque-replica-worker-" : "estoque-primary-worker-"));

        String kafka = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        EventoEstoquePublisher publisher = (!replica && kafka != null && !kafka.isBlank())
                ? new KafkaEventoEstoquePublisher(kafka)
                : new NoopEventoEstoquePublisher();
        VerificadorPeriodicoEstoque verificador = replica
                ? null
                : new VerificadorPeriodicoEstoque(repository, publisher, 5);

        SistemaDeEstoquesService service = new SistemaDeEstoquesService(executor, repository, publisher, replica);
        Server server = ServerBuilder.forPort(porta)
                .directExecutor()
                .intercept(new AutorizacaoInterceptor())
                .addService(service)
                .build()
                .start();

        System.out.printf("Sistema de Estoques %s iniciado na porta %d; dados=%s; workers=%d%n",
                replica ? "RÉPLICA" : "PRIMÁRIO", porta, diretorio.toAbsolutePath(), workers);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (verificador != null) {
                verificador.close();
            }
            publisher.close();
            executor.shutdownNow();
        }, "shutdown-estoque"));

        server.awaitTermination();
    }
}
