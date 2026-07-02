package br.ufg.marmitaria.cardapio.servidor;

import br.ufg.marmitaria.cardapio.estoque.EstoqueGrpcClient;
import br.ufg.marmitaria.cardapio.eventos.AtualizadorStatusKafka;
import br.ufg.marmitaria.cardapio.eventos.EventoPedidoPublisher;
import br.ufg.marmitaria.cardapio.modelo.PedidoStore;
import br.ufg.marmitaria.cardapio.servico.CardapioI;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.nio.file.Path;

public final class ServidorCardapioIce {
    private ServidorCardapioIce() {
    }

    public static void main(String[] args) throws Exception {
        String estoquePrimario = env("ESTOQUE_GRPC_PRIMARIO", "localhost:50051");
        String estoqueReplica = env("ESTOQUE_GRPC_REPLICA", "localhost:50054");
        String kafka = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String endpoint = env("CARDAPIO_ICE_ENDPOINT", "tcp -h 0.0.0.0 -p 10000");
        Path dados = Path.of(env("DIRETORIO_PEDIDOS", "dados-pedidos"));

        try (Communicator communicator = Util.initialize(args);
             EstoqueGrpcClient estoque = new EstoqueGrpcClient(estoquePrimario, estoqueReplica);
             EventoPedidoPublisher publisher = new EventoPedidoPublisher(kafka)) {

            // O runtime Ice atende múltiplos clientes com seu pool; o estado da aplicação
            // permanece protegido no PedidoStore e no serviço gRPC de estoque.
            communicator.getProperties().setProperty("Ice.ThreadPool.Server.Size", "8");
            communicator.getProperties().setProperty("Ice.ThreadPool.Server.SizeMax", "32");

            PedidoStore store = new PedidoStore(dados);
            try (AtualizadorStatusKafka atualizador = new AtualizadorStatusKafka(kafka, store)) {
                ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("CardapioAdapter", endpoint);
                adapter.add(new CardapioI(estoque, store, publisher), Util.stringToIdentity("Cardapio"));
                adapter.activate();

                System.out.println("Servidor Ice do cardápio iniciado em: " + endpoint);
                System.out.println("Estoque primário: " + estoquePrimario + "; réplica: " + estoqueReplica);
                communicator.waitForShutdown();
            }
        }
    }

    private static String env(String nome, String padrao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? padrao : valor;
    }
}
