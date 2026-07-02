package br.ufg.marmitaria.estoques.eventos;

import br.ufg.marmitaria.estoques.modelo.AlertaEstoque;
import br.ufg.marmitaria.estoques.modelo.InventarioEstado;
import br.ufg.marmitaria.estoques.modelo.ResultadoOperacao;
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

/** Publicador assíncrono. Nenhuma chamada de rede Kafka ocorre dentro do lock do repositório. */
public final class KafkaEventoEstoquePublisher implements EventoEstoquePublisher {
    private static final Logger LOGGER = Logger.getLogger(KafkaEventoEstoquePublisher.class.getName());

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String topicoAlertas;
    private final String topicoSnapshots;
    private final String topicoConsumo;

    public KafkaEventoEstoquePublisher(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "estoque-service");
        this.producer = new KafkaProducer<>(props);
        this.topicoAlertas = env("TOPICO_ALERTAS_ESTOQUE", "alertas.estoque");
        this.topicoSnapshots = env("TOPICO_SNAPSHOTS_ESTOQUE", "estoque.snapshots");
        this.topicoConsumo = env("TOPICO_ITENS_CONSUMIDOS", "itens.consumidos");
    }

    @Override
    public void publicarResultado(String tipoEvento, String operacaoId, ResultadoOperacao resultado) {
        if (!resultado.sucesso() || resultado.jaProcessada()) {
            return;
        }
        publicarSnapshot(resultado.snapshot(), tipoEvento, operacaoId);
        for (AlertaEstoque alerta : resultado.alertas()) {
            publicar(topicoAlertas, alerta.item(), eventoAlerta(alerta));
        }
        if (!resultado.insumosConsumidos().isEmpty()) {
            Map<String, Object> evento = new LinkedHashMap<>();
            evento.put("evento_id", "CONSUMO:" + operacaoId);
            evento.put("tipo", "ITENS_CONSUMIDOS_NA_PRODUCAO");
            evento.put("operacao_id", operacaoId);
            evento.put("insumos", resultado.insumosConsumidos());
            evento.put("marmitas_produzidas", resultado.marmitasProduzidas());
            evento.put("versao_inventario", resultado.snapshot().versao());
            evento.put("timestamp", Instant.now().toString());
            publicar(topicoConsumo, operacaoId, evento);
        }
    }

    @Override
    public void publicarSnapshotPeriodico(InventarioEstado snapshot) {
        publicarSnapshot(snapshot, "SNAPSHOT_PERIODICO", "snapshot:" + snapshot.versao());
    }

    private static Map<String, Object> eventoAlerta(AlertaEstoque alerta) {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("alerta_id", alerta.alertaId());
        evento.put("tipo", alerta.tipo());
        evento.put("item", alerta.item());
        evento.put("quantidade_atual", alerta.quantidadeAtual());
        evento.put("limite", alerta.limite());
        evento.put("versao_inventario", alerta.versaoInventario());
        evento.put("mensagem", alerta.mensagem());
        evento.put("timestamp", Instant.now().toString());
        return evento;
    }

    private void publicarSnapshot(InventarioEstado snapshot, String origem, String operacaoId) {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("evento_id", "SNAPSHOT:" + snapshot.versao());
        evento.put("tipo", "SNAPSHOT_ESTOQUE");
        evento.put("origem", origem);
        evento.put("operacao_id", operacaoId);
        evento.put("snapshot", snapshot);
        evento.put("timestamp", Instant.now().toString());
        publicar(topicoSnapshots, "inventario", evento);
    }

    private void publicar(String topico, String chave, Object valor) {
        try {
            String json = mapper.writeValueAsString(valor);
            producer.send(new ProducerRecord<>(topico, chave, json), (metadata, erro) -> {
                if (erro != null) {
                    LOGGER.log(Level.WARNING, "Falha ao publicar no tópico " + topico, erro);
                }
            });
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.WARNING, "Falha ao serializar evento de estoque", e);
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
