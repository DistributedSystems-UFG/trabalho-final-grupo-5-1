package br.ufg.marmitaria.cardapio.estoque;

import br.ufg.marmitaria.cardapio.modelo.ItemPedidoInterno;
import br.ufg.marmitaria.estoques.grpc.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cliente gRPC síncrono usado pelo servidor Ice.
 * Escritas vão somente para a primária; leituras podem falhar para a réplica.
 */
public final class EstoqueGrpcClient implements AutoCloseable {
    private final ManagedChannel canalPrimario;
    private final ManagedChannel canalReplica;
    private final SistemaDeEstoquesGrpc.SistemaDeEstoquesBlockingStub primario;
    private final SistemaDeEstoquesGrpc.SistemaDeEstoquesBlockingStub replica;
    private final ObjectMapper mapper = new ObjectMapper();

    public EstoqueGrpcClient(String enderecoPrimario, String enderecoReplica) {
        String apiKey = System.getenv("API_KEY_SISTEMA");
        this.canalPrimario = canal(enderecoPrimario);
        this.primario = comApiKey(SistemaDeEstoquesGrpc.newBlockingStub(canalPrimario), apiKey);
        if (enderecoReplica != null && !enderecoReplica.isBlank()) {
            this.canalReplica = canal(enderecoReplica);
            this.replica = comApiKey(SistemaDeEstoquesGrpc.newBlockingStub(canalReplica), apiKey);
        } else {
            this.canalReplica = null;
            this.replica = null;
        }
    }

    public ReservaResultado reservar(String pedidoId, List<ItemPedidoInterno> itens) {
        ReservaMarmitasRequest request = ReservaMarmitasRequest.newBuilder()
                .setOperacaoId("reserva:" + pedidoId)
                .setPedidoId(pedidoId)
                .addAllItens(paraProto(itens))
                .build();
        ReservaMarmitasResponse response = primario.withDeadlineAfter(5, TimeUnit.SECONDS).reservarMarmitas(request);
        return new ReservaResultado(response.getSucesso(), response.getMensagem());
    }

    public PlanoResultado planejar(List<ItemPedidoInterno> itens) {
        PlanejarAtendimentoRequest request = PlanejarAtendimentoRequest.newBuilder()
                .addAllItens(paraProto(itens))
                .build();
        PlanejarAtendimentoResponse response = executarLeitura(stub ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS).planejarAtendimento(request));
        return new PlanoResultado(
                response.getAtendeImediatamente(),
                response.getPodeProduzirFaltantes(),
                response.getDeveFecharLoja(),
                paraInterno(response.getOfertaParcialList()),
                paraInterno(response.getProducaoNecessariaList()),
                response.getMensagem());
    }

    public String consultarDisponibilidadeJson() {
        EstoqueGeralResponse response = executarLeitura(stub ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .consultarEstoqueGeral(ConsultaEstoqueRequest.getDefaultInstance()));
        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put("versao", response.getSnapshot().getVersao());
        saida.put("loja_aberta", response.getSnapshot().getLojaAberta());
        saida.put("veio_da_replica", response.getVeioDaReplica());
        Map<String, Integer> marmitas = new LinkedHashMap<>();
        for (QuantidadeMarmitaEstoque item : response.getSnapshot().getMarmitasList()) {
            marmitas.put(chave(item.getTamanho(), item.getProteina()), item.getQuantidade());
        }
        Map<String, Integer> insumos = new LinkedHashMap<>();
        for (QuantidadeInsumo item : response.getSnapshot().getInsumosList()) {
            insumos.put(item.getTipo().name(), item.getQuantidade());
        }
        saida.put("marmitas", marmitas);
        saida.put("insumos", insumos);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(saida);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao montar disponibilidade.", e);
        }
    }

    public void fecharLoja(String pedidoId, String motivo) {
        primario.withDeadlineAfter(5, TimeUnit.SECONDS).alterarEstadoLoja(
                AlterarEstadoLojaRequest.newBuilder()
                        .setOperacaoId("fechar-loja:" + pedidoId)
                        .setAberta(false)
                        .setMotivo(motivo)
                        .build());
    }

    private <T> T executarLeitura(LeituraGrpc<T> leitura) {
        try {
            return leitura.executar(primario);
        } catch (StatusRuntimeException erroPrimario) {
            if (replica == null) {
                throw erroPrimario;
            }
            return leitura.executar(replica);
        }
    }

    private static SistemaDeEstoquesGrpc.SistemaDeEstoquesBlockingStub comApiKey(
            SistemaDeEstoquesGrpc.SistemaDeEstoquesBlockingStub stub,
            String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return stub;
        }
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), apiKey);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static ManagedChannel canal(String endereco) {
        String[] partes = endereco.split(":", 2);
        if (partes.length != 2) {
            throw new IllegalArgumentException("Endereço gRPC inválido: " + endereco);
        }
        return ManagedChannelBuilder.forAddress(partes[0], Integer.parseInt(partes[1]))
                .usePlaintext()
                .build();
    }

    private static List<ItemMarmita> paraProto(List<ItemPedidoInterno> itens) {
        List<ItemMarmita> resultado = new ArrayList<>();
        for (ItemPedidoInterno item : itens) {
            resultado.add(ItemMarmita.newBuilder()
                    .setTamanho(TipoMarmita.valueOf("MARMITA_" + item.tamanho()))
                    .setProteina(TipoProteina.valueOf(item.proteina()))
                    .setQuantidade(item.quantidade())
                    .build());
        }
        return resultado;
    }

    private static List<ItemPedidoInterno> paraInterno(List<ItemMarmita> itens) {
        List<ItemPedidoInterno> resultado = new ArrayList<>();
        for (ItemMarmita item : itens) {
            resultado.add(new ItemPedidoInterno(
                    item.getTamanho().name().replace("MARMITA_", ""),
                    item.getProteina().name(),
                    item.getQuantidade()));
        }
        return List.copyOf(resultado);
    }

    private static String chave(TipoMarmita tamanho, TipoProteina proteina) {
        return tamanho.name().replace("MARMITA_", "") + "_" + proteina.name();
    }

    @Override
    public void close() {
        canalPrimario.shutdownNow();
        if (canalReplica != null) {
            canalReplica.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface LeituraGrpc<T> {
        T executar(SistemaDeEstoquesGrpc.SistemaDeEstoquesBlockingStub stub);
    }

    public record ReservaResultado(boolean sucesso, String mensagem) {
    }

    public record PlanoResultado(
            boolean atendeImediatamente,
            boolean podeProduzir,
            boolean deveFechar,
            List<ItemPedidoInterno> ofertaParcial,
            List<ItemPedidoInterno> producaoNecessaria,
            String mensagem) {
    }
}
