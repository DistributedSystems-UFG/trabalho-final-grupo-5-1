package br.ufg.marmitaria.estoques.servico;

import br.ufg.marmitaria.estoques.dominio.ChaveMarmita;
import br.ufg.marmitaria.estoques.dominio.Insumo;
import br.ufg.marmitaria.estoques.dominio.TamanhoMarmita;
import br.ufg.marmitaria.estoques.dominio.TipoProteina;
import br.ufg.marmitaria.estoques.grpc.AlertaEstoque;
import br.ufg.marmitaria.estoques.grpc.ItemMarmita;
import br.ufg.marmitaria.estoques.grpc.QuantidadeInsumo;
import br.ufg.marmitaria.estoques.grpc.QuantidadeMarmitaEstoque;
import br.ufg.marmitaria.estoques.grpc.SnapshotInventario;
import br.ufg.marmitaria.estoques.grpc.TipoAlerta;
import br.ufg.marmitaria.estoques.grpc.TipoInsumo;
import br.ufg.marmitaria.estoques.grpc.TipoMarmita;
import br.ufg.marmitaria.estoques.modelo.InventarioEstado;
import br.ufg.marmitaria.estoques.modelo.ItemPedido;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProtoMapper {
    private ProtoMapper() {
    }

    static ItemPedido paraModelo(ItemMarmita item) {
        return new ItemPedido(
                new ChaveMarmita(mapearTamanho(item.getTamanho()), mapearProteina(item.getProteina())),
                item.getQuantidade());
    }

    static List<ItemPedido> paraModelo(List<ItemMarmita> itens) {
        List<ItemPedido> resultado = new ArrayList<>();
        for (ItemMarmita item : itens) {
            resultado.add(paraModelo(item));
        }
        return List.copyOf(resultado);
    }

    static Insumo mapearInsumo(TipoInsumo tipo) {
        return switch (tipo) {
            case ARROZ -> Insumo.ARROZ;
            case FEIJAO -> Insumo.FEIJAO;
            case MACARRAO -> Insumo.MACARRAO;
            case VERDURAS -> Insumo.VERDURAS;
            case CARNE_BOVINA -> Insumo.CARNE_BOVINA;
            case CARNE_SUINA -> Insumo.CARNE_SUINA;
            case CARNE_FRANGO -> Insumo.CARNE_FRANGO;
            case EMBALAGEM_P -> Insumo.EMBALAGEM_P;
            case EMBALAGEM_M -> Insumo.EMBALAGEM_M;
            case EMBALAGEM_G -> Insumo.EMBALAGEM_G;
            case TIPO_INSUMO_NAO_ESPECIFICADO, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Informe um insumo válido.");
        };
    }

    static Map<Insumo, Integer> mapearReposicoes(List<QuantidadeInsumo> itens) {
        Map<Insumo, Integer> resultado = new LinkedHashMap<>();
        for (QuantidadeInsumo item : itens) {
            resultado.merge(mapearInsumo(item.getTipo()), item.getQuantidade(), Math::addExact);
        }
        return resultado;
    }

    static SnapshotInventario paraProto(InventarioEstado estado) {
        SnapshotInventario.Builder builder = SnapshotInventario.newBuilder()
                .setVersao(estado.versao())
                .setLojaAberta(estado.lojaAberta())
                .setAtualizadoEmEpochMs(estado.atualizadoEmEpochMs());

        estado.marmitas().forEach((codigo, quantidade) -> {
            ChaveMarmita chave = ChaveMarmita.deCodigo(codigo);
            builder.addMarmitas(QuantidadeMarmitaEstoque.newBuilder()
                    .setTamanho(paraProto(chave.tamanho()))
                    .setProteina(paraProto(chave.proteina()))
                    .setQuantidade(quantidade)
                    .build());
        });
        estado.insumos().forEach((nome, quantidade) -> builder.addInsumos(
                QuantidadeInsumo.newBuilder()
                        .setTipo(paraProto(Insumo.valueOf(nome)))
                        .setQuantidade(quantidade)
                        .build()));
        return builder.build();
    }

    static InventarioEstado paraModelo(SnapshotInventario snapshot) {
        Map<String, Integer> marmitas = new LinkedHashMap<>();
        for (QuantidadeMarmitaEstoque item : snapshot.getMarmitasList()) {
            ChaveMarmita chave = new ChaveMarmita(mapearTamanho(item.getTamanho()), mapearProteina(item.getProteina()));
            marmitas.put(chave.codigo(), item.getQuantidade());
        }
        Map<String, Integer> insumos = new LinkedHashMap<>();
        for (QuantidadeInsumo item : snapshot.getInsumosList()) {
            insumos.put(mapearInsumo(item.getTipo()).name(), item.getQuantidade());
        }
        return new InventarioEstado(
                snapshot.getVersao(),
                snapshot.getLojaAberta(),
                marmitas,
                insumos,
                java.util.Set.of(),
                snapshot.getAtualizadoEmEpochMs());
    }

    static ItemMarmita itemParaProto(String codigo, int quantidade) {
        ChaveMarmita chave = ChaveMarmita.deCodigo(codigo);
        return ItemMarmita.newBuilder()
                .setTamanho(paraProto(chave.tamanho()))
                .setProteina(paraProto(chave.proteina()))
                .setQuantidade(quantidade)
                .build();
    }

    static QuantidadeInsumo insumoParaProto(String nome, int quantidade) {
        return QuantidadeInsumo.newBuilder()
                .setTipo(paraProto(Insumo.valueOf(nome)))
                .setQuantidade(quantidade)
                .build();
    }

    static AlertaEstoque alertaParaProto(br.ufg.marmitaria.estoques.modelo.AlertaEstoque alerta) {
        TipoAlerta tipo = switch (alerta.tipo()) {
            case "MARMITA_BAIXA" -> TipoAlerta.MARMITA_BAIXA;
            case "INSUMO_BAIXO" -> TipoAlerta.INSUMO_BAIXO;
            case "LOJA_FECHADA" -> TipoAlerta.LOJA_FECHADA;
            default -> TipoAlerta.TIPO_ALERTA_NAO_ESPECIFICADO;
        };
        return AlertaEstoque.newBuilder()
                .setAlertaId(alerta.alertaId())
                .setTipo(tipo)
                .setItem(alerta.item())
                .setQuantidadeAtual(alerta.quantidadeAtual())
                .setLimite(alerta.limite())
                .setVersaoInventario(alerta.versaoInventario())
                .setMensagem(alerta.mensagem())
                .build();
    }

    static TamanhoMarmita mapearTamanho(TipoMarmita tipo) {
        return switch (tipo) {
            case MARMITA_P -> TamanhoMarmita.P;
            case MARMITA_M -> TamanhoMarmita.M;
            case MARMITA_G -> TamanhoMarmita.G;
            case TIPO_MARMITA_NAO_ESPECIFICADO, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Informe um tamanho de marmita válido.");
        };
    }

    static TipoProteina mapearProteina(br.ufg.marmitaria.estoques.grpc.TipoProteina tipo) {
        return switch (tipo) {
            case BOVINA -> TipoProteina.BOVINA;
            case SUINA -> TipoProteina.SUINA;
            case FRANGO -> TipoProteina.FRANGO;
            case TIPO_PROTEINA_NAO_ESPECIFICADO, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Informe uma proteína válida.");
        };
    }

    static TipoMarmita paraProto(TamanhoMarmita tamanho) {
        return switch (tamanho) {
            case P -> TipoMarmita.MARMITA_P;
            case M -> TipoMarmita.MARMITA_M;
            case G -> TipoMarmita.MARMITA_G;
        };
    }

    static br.ufg.marmitaria.estoques.grpc.TipoProteina paraProto(TipoProteina proteina) {
        return switch (proteina) {
            case BOVINA -> br.ufg.marmitaria.estoques.grpc.TipoProteina.BOVINA;
            case SUINA -> br.ufg.marmitaria.estoques.grpc.TipoProteina.SUINA;
            case FRANGO -> br.ufg.marmitaria.estoques.grpc.TipoProteina.FRANGO;
        };
    }

    static TipoInsumo paraProto(Insumo insumo) {
        return TipoInsumo.valueOf(insumo.name());
    }
}
