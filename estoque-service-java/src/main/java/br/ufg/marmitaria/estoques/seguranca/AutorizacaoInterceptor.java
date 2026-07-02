package br.ufg.marmitaria.estoques.seguranca;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Autorização opcional por papel. Para a demonstração local ela fica desligada
 * por padrão; em AWS, use AUTORIZACAO_HABILITADA=true e configure as chaves.
 */
public final class AutorizacaoInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> API_KEY =
            Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);

    private final boolean habilitada;
    private final Map<String, Papel> papeisPorChave;

    public AutorizacaoInterceptor() {
        this.habilitada = Boolean.parseBoolean(env("AUTORIZACAO_HABILITADA", "false"));
        this.papeisPorChave = new HashMap<>();
        registrar("API_KEY_SISTEMA", Papel.SISTEMA);
        registrar("API_KEY_COMPRAS", Papel.COMPRAS);
        registrar("API_KEY_GERENTE", Papel.GERENTE);
        registrar("API_KEY_DONO", Papel.DONO);
        registrar("API_KEY_REPLICADOR", Papel.REPLICADOR);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        if (!habilitada) {
            return next.startCall(call, headers);
        }

        Papel papel = papeisPorChave.get(headers.get(API_KEY));
        String metodo = call.getMethodDescriptor().getBareMethodName();
        if (papel == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("API key ausente ou inválida."), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        if (!permitidos(metodo).contains(papel)) {
            call.close(Status.PERMISSION_DENIED.withDescription(
                    "O papel " + papel + " não pode executar " + metodo + "."), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }

    private static Set<Papel> permitidos(String metodo) {
        return switch (metodo) {
            case "ConsultarEstoqueGeral", "PlanejarAtendimento" -> EnumSet.allOf(Papel.class);
            case "ReservarMarmitas", "ProduzirMarmitas", "ProduzirEReservar" ->
                    EnumSet.of(Papel.SISTEMA, Papel.DONO);
            case "ReporInsumos" -> EnumSet.of(Papel.COMPRAS, Papel.SISTEMA, Papel.DONO);
            case "AjustarEstoqueMarmita", "AjustarEstoqueInsumo", "AlterarEstadoLoja" ->
                    EnumSet.of(Papel.GERENTE, Papel.DONO);
            case "AplicarSnapshotReplica" -> EnumSet.of(Papel.REPLICADOR, Papel.DONO);
            default -> EnumSet.noneOf(Papel.class);
        };
    }

    private void registrar(String variavel, Papel papel) {
        String chave = System.getenv(variavel);
        if (chave != null && !chave.isBlank()) {
            papeisPorChave.put(chave, papel);
        }
    }

    private static String env(String nome, String padrao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? padrao : valor;
    }

    private enum Papel {
        SISTEMA,
        COMPRAS,
        GERENTE,
        DONO,
        REPLICADOR
    }
}
