package br.ufg.marmitaria.cardapio.eventos;

import br.ufg.marmitaria.cardapio.modelo.PedidoStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** KafkaConsumer confinado a uma única thread; não é compartilhado com as threads Ice. */
public final class AtualizadorStatusKafka implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(AtualizadorStatusKafka.class.getName());
    private final AtomicBoolean executando = new AtomicBoolean(true);
    private final Thread thread;

    public AtualizadorStatusKafka(String bootstrapServers, PedidoStore store) {
        this.thread = new Thread(() -> executar(bootstrapServers, store), "consumer-status-pedidos");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void executar(String bootstrapServers, PedidoStore store) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "cardapio-status-pedidos");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        String vendas = env("TOPICO_VENDAS_CONCRETIZADAS", "vendas.concretizadas");
        String producao = env("TOPICO_RESULTADO_PRODUCAO", "pedidos.producao.resultado");
        ObjectMapper mapper = new ObjectMapper();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(vendas, producao));
            while (executando.get()) {
                var registros = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> registro : registros) {
                    try {
                        JsonNode evento = mapper.readTree(registro.value());
                        String pedidoId = evento.path("pedido_id").asText();
                        String tipo = evento.path("tipo").asText();
                        if (pedidoId.isBlank()) {
                            continue;
                        }
                        if ("VENDA_CONCRETIZADA".equals(tipo)) {
                            store.atualizar(pedidoId, p -> p.comStatus(
                                    "CONCLUIDO",
                                    "Pedido concluído e venda concretizada.",
                                    0));
                        } else if ("PRODUCAO_PEDIDO_CONCLUIDA".equals(tipo)) {
                            boolean sucesso = evento.path("sucesso").asBoolean(false);
                            if (sucesso) {
                                store.atualizar(pedidoId, p -> p.comStatus(
                                        "EMPROCESSAMENTO",
                                        "Produção concluída; pedido encaminhado aos workers.",
                                        2));
                            } else {
                                String mensagem = evento.path("mensagem").asText("Produção não realizada.");
                                store.atualizar(pedidoId, p -> p.comStatus("RECUSADO", mensagem, 0));
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Falha ao processar evento de status", e);
                    }
                }
                if (!registros.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (Exception e) {
            if (executando.get()) {
                LOGGER.log(Level.SEVERE, "Consumer de status foi encerrado com erro", e);
            }
        }
    }

    @Override
    public void close() {
        executando.set(false);
        thread.interrupt();
    }

    private static String env(String nome, String padrao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? padrao : valor;
    }
}
