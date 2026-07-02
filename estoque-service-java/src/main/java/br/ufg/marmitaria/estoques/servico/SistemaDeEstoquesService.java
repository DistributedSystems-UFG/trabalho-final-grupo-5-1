package br.ufg.marmitaria.estoques.servico;

import br.ufg.marmitaria.estoques.dominio.ChaveMarmita;
import br.ufg.marmitaria.estoques.eventos.EventoEstoquePublisher;
import br.ufg.marmitaria.estoques.grpc.*;
import br.ufg.marmitaria.estoques.modelo.PlanoAtendimento;
import br.ufg.marmitaria.estoques.modelo.ResultadoOperacao;
import br.ufg.marmitaria.estoques.repositorio.InventarioArquivoRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Serviço stateless: o estado compartilhado fica encapsulado no repositório thread-safe. */
public final class SistemaDeEstoquesService extends SistemaDeEstoquesGrpc.SistemaDeEstoquesImplBase {
    private static final Logger LOGGER = Logger.getLogger(SistemaDeEstoquesService.class.getName());
    private static final int ATRASO_MAXIMO_MS = 10_000;

    private final ExecutorService executor;
    private final InventarioArquivoRepository repository;
    private final EventoEstoquePublisher publisher;
    private final boolean replica;

    public SistemaDeEstoquesService(
            ExecutorService executor,
            InventarioArquivoRepository repository,
            EventoEstoquePublisher publisher,
            boolean replica) {
        this.executor = executor;
        this.repository = repository;
        this.publisher = publisher;
        this.replica = replica;
    }

    @Override
    public void consultarEstoqueGeral(
            ConsultaEstoqueRequest request,
            StreamObserver<EstoqueGeralResponse> observer) {
        executarRpc(observer, () -> {
            simularAtraso(request.getAtrasoSimuladoMs());
            return EstoqueGeralResponse.newBuilder()
                    .setSnapshot(ProtoMapper.paraProto(repository.consultar()))
                    .setThreadProcessamento(Thread.currentThread().getName())
                    .setVeioDaReplica(replica)
                    .build();
        });
    }

    @Override
    public void planejarAtendimento(
            PlanejarAtendimentoRequest request,
            StreamObserver<PlanejarAtendimentoResponse> observer) {
        executarRpc(observer, () -> {
            PlanoAtendimento plano = repository.planejar(ProtoMapper.paraModelo(request.getItensList()));
            PlanejarAtendimentoResponse.Builder resposta = PlanejarAtendimentoResponse.newBuilder()
                    .setAtendeImediatamente(plano.atendeImediatamente())
                    .setPodeProduzirFaltantes(plano.podeProduzirFaltantes())
                    .setDeveFecharLoja(plano.deveFecharLoja())
                    .setSnapshot(ProtoMapper.paraProto(plano.snapshot()))
                    .setMensagem(plano.mensagem())
                    .setThreadProcessamento(Thread.currentThread().getName());
            plano.faltantes().forEach((k, v) -> resposta.addFaltantes(ProtoMapper.itemParaProto(k, v)));
            plano.ofertaParcial().forEach((k, v) -> resposta.addOfertaParcial(ProtoMapper.itemParaProto(k, v)));
            plano.producaoNecessaria().forEach((k, v) -> resposta.addProducaoNecessaria(ProtoMapper.itemParaProto(k, v)));
            return resposta.build();
        });
    }

    @Override
    public void reservarMarmitas(
            ReservaMarmitasRequest request,
            StreamObserver<ReservaMarmitasResponse> observer) {
        executarRpc(observer, () -> {
            exigirPrimaria();
            ResultadoOperacao resultado = repository.reservar(
                    request.getOperacaoId(),
                    ProtoMapper.paraModelo(request.getItensList()));
            if (resultado.sucesso()) {
                publisher.publicarResultado("RESERVA_MARMITAS", request.getOperacaoId(), resultado);
            }
            ReservaMarmitasResponse.Builder resposta = ReservaMarmitasResponse.newBuilder()
                    .setSucesso(resultado.sucesso())
                    .setJaProcessada(resultado.jaProcessada())
                    .setMensagem(resultado.mensagem())
                    .setSnapshot(ProtoMapper.paraProto(resultado.snapshot()))
                    .setThreadProcessamento(Thread.currentThread().getName());
            resultado.faltantes().forEach((k, v) -> {
                if (k.contains("_")) {
                    resposta.addFaltantes(ProtoMapper.itemParaProto(k, v));
                }
            });
            resultado.alertas().forEach(a -> resposta.addAlertas(ProtoMapper.alertaParaProto(a)));
            return resposta.build();
        });
    }

    @Override
    public void produzirMarmitas(
            ProduzirMarmitasRequest request,
            StreamObserver<OperacaoInventarioResponse> observer) {
        executarRpc(observer, () -> {
            exigirPrimaria();
            ResultadoOperacao resultado = repository.produzir(
                    request.getOperacaoId(),
                    ProtoMapper.paraModelo(request.getItem()));
            publisher.publicarResultado("PRODUCAO_MARMITAS", request.getOperacaoId(), resultado);
            return respostaOperacao(resultado);
        });
    }

    @Override
    public void produzirEReservar(
            ProduzirEReservarRequest request,
            StreamObserver<OperacaoInventarioResponse> observer) {
        executarRpc(observer, () -> {
            exigirPrimaria();
            ResultadoOperacao resultado = repository.produzirEReservar(
                    request.getOperacaoId(),
                    ProtoMapper.paraModelo(request.getItensPedidoList()),
                    request.getTamanhoLote());
            publisher.publicarResultado("PRODUCAO_E_RESERVA", request.getOperacaoId(), resultado);
            return respostaOperacao(resultado);
        });
    }

    @Override
    public void reporInsumos(
            ReporInsumosRequest request,
            StreamObserver<OperacaoInventarioResponse> observer) {
        executarRpc(observer, () -> {
            exigirPrimaria();
            ResultadoOperacao resultado = repository.reporInsumos(
                    request.getOperacaoId(),
                    ProtoMapper.mapearReposicoes(request.getItensList()));
            publisher.publicarResultado("REPOSICAO_INSUMOS", request.getOperacaoId(), resultado);
            return respostaOperacao(resultado);
        });
    }

    @Override
    public void ajustarEstoqueMarmita(
            AjusteEstoqueMarmitaRequest request,
            StreamObserver<OperacaoInventarioResponse> observer) {
        executarRpc(observer, () -> {
            exigirPrimaria();
            ResultadoOperacao resultado = repository.ajustarMarmita(
                    request.getOperacaoId(),
                    new ChaveMarmita(
                            ProtoMapper.mapearTamanho(request.getTamanho()),
                            ProtoMapper.mapearProteina(request.getProteina())),
                    request.getVariacao());
            publisher.publicarResultado("AJUSTE_MARMITA", request.getOperacaoId(), resultado);
            return respostaOperacao(resultado);
        });
    }

    @Override
    public void ajustarEstoqueInsumo(
            AjusteEstoqueInsumoRequest request,
            StreamObserver<OperacaoInventarioResponse> observer) {
        executarRpc(observer, () -> {
            exigirPrimaria();
            ResultadoOperacao resultado = repository.ajustarInsumo(
                    request.getOperacaoId(),
                    ProtoMapper.mapearInsumo(request.getTipo()),
                    request.getVariacao());
            publisher.publicarResultado("AJUSTE_INSUMO", request.getOperacaoId(), resultado);
            return respostaOperacao(resultado);
        });
    }

    @Override
    public void alterarEstadoLoja(
            AlterarEstadoLojaRequest request,
            StreamObserver<OperacaoInventarioResponse> observer) {
        executarRpc(observer, () -> {
            exigirPrimaria();
            ResultadoOperacao resultado = repository.alterarEstadoLoja(
                    request.getOperacaoId(),
                    request.getAberta());
            publisher.publicarResultado("ALTERACAO_ESTADO_LOJA", request.getOperacaoId(), resultado);
            return respostaOperacao(resultado);
        });
    }

    @Override
    public void aplicarSnapshotReplica(
            AplicarSnapshotReplicaRequest request,
            StreamObserver<OperacaoInventarioResponse> observer) {
        executarRpc(observer, () -> {
            if (!replica) {
                throw Status.FAILED_PRECONDITION
                        .withDescription("Esta operação só pode ser executada na instância réplica.")
                        .asRuntimeException();
            }
            ResultadoOperacao resultado = repository.aplicarSnapshotReplica(
                    request.getOperacaoId(),
                    ProtoMapper.paraModelo(request.getSnapshot()));
            return respostaOperacao(resultado);
        });
    }

    private OperacaoInventarioResponse respostaOperacao(ResultadoOperacao resultado) {
        OperacaoInventarioResponse.Builder resposta = OperacaoInventarioResponse.newBuilder()
                .setSucesso(resultado.sucesso())
                .setJaProcessada(resultado.jaProcessada())
                .setMensagem(resultado.mensagem())
                .setSnapshot(ProtoMapper.paraProto(resultado.snapshot()))
                .setThreadProcessamento(Thread.currentThread().getName());
        resultado.alertas().forEach(a -> resposta.addAlertas(ProtoMapper.alertaParaProto(a)));
        resultado.insumosConsumidos().forEach((k, v) -> resposta.addInsumosConsumidos(ProtoMapper.insumoParaProto(k, v)));
        resultado.marmitasProduzidas().forEach((k, v) -> resposta.addMarmitasProduzidas(ProtoMapper.itemParaProto(k, v)));
        return resposta.build();
    }

    private void exigirPrimaria() {
        if (replica) {
            throw Status.FAILED_PRECONDITION
                    .withDescription("A réplica é somente leitura; escritas devem ir para a primária.")
                    .asRuntimeException();
        }
    }

    private <T> void executarRpc(StreamObserver<T> observer, TarefaRpc<T> tarefa) {
        try {
            executor.execute(() -> {
                try {
                    T resposta = tarefa.executar();
                    observer.onNext(resposta);
                    observer.onCompleted();
                } catch (IllegalArgumentException e) {
                    observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e).asRuntimeException());
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Falha de I/O no serviço de estoques", e);
                    observer.onError(Status.INTERNAL.withDescription("Falha ao acessar o inventário.").withCause(e).asRuntimeException());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    observer.onError(Status.CANCELLED.withDescription("Operação interrompida.").withCause(e).asRuntimeException());
                } catch (RuntimeException e) {
                    observer.onError(e);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Falha inesperada", e);
                    observer.onError(Status.INTERNAL.withDescription("Falha inesperada.").withCause(e).asRuntimeException());
                }
            });
        } catch (RejectedExecutionException e) {
            observer.onError(Status.UNAVAILABLE.withDescription("Servidor sobrecarregado ou encerrando.").withCause(e).asRuntimeException());
        }
    }

    private static void simularAtraso(int atrasoMs) throws InterruptedException {
        if (atrasoMs < 0 || atrasoMs > ATRASO_MAXIMO_MS) {
            throw new IllegalArgumentException("O atraso deve estar entre 0 e " + ATRASO_MAXIMO_MS + " ms.");
        }
        if (atrasoMs > 0) {
            Thread.sleep(atrasoMs);
        }
    }

    @FunctionalInterface
    private interface TarefaRpc<T> {
        T executar() throws Exception;
    }
}
