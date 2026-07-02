package br.ufg.marmitaria.cardapio.eventos;

import br.ufg.marmitaria.cardapio.modelo.PedidoInterno;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EventoPedidoPublisher implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(EventoPedidoPublisher.class.getName());
    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String topicoConfirmados;
    private final String topicoProducao;

    public EventoPedidoPublisher(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "cardapio-ice");
        this.producer = new KafkaProducer<>(props);
        this.topicoConfirmados = env("TOPICO_PEDIDOS_CONFIRMADOS", "pedidos.confirmados");
        this.topicoProducao = env("TOPICO_PEDIDOS_PRODUCAO", "pedidos.producao");
    }

    public void publicarPedidoConfirmado(PedidoInterno pedido) {
        publicar(topicoConfirmados, pedido.pedidoId(), evento("PEDIDO_CONFIRMADO", pedido));
    }

    public void publicarPedidoParaProducao(PedidoInterno pedido) {
        publicar(topicoProducao, pedido.pedidoId(), evento("PEDIDO_AGUARDANDO_PRODUCAO", pedido));
    }

    private Map<String, Object> evento(String tipo, PedidoInterno pedido) {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("evento_id", tipo + ":" + pedido.pedidoId());
        evento.put("tipo", tipo);
        evento.put("pedido_id", pedido.pedidoId());
        evento.put("cliente_id", pedido.clienteId());
        evento.put("itens", pedido.itensOferecidos().isEmpty()
                ? pedido.itensSolicitados()
                : pedido.itensOferecidos());
        evento.put("valor_total", pedido.valorTotal());
        evento.put("timestamp", Instant.now().toString());
        return evento;
    }

    private void publicar(String topico, String chave, Object evento) {
        try {
            producer.send(new ProducerRecord<>(topico, chave, mapper.writeValueAsString(evento)), (metadata, erro) -> {
                if (erro != null) {
                    LOGGER.log(Level.WARNING, "Falha ao publicar evento de pedido", erro);
                }
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar evento de pedido.", e);
        }
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
    }

    private static String env(String nome, String padrao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? padrao : valor;
    }
}
