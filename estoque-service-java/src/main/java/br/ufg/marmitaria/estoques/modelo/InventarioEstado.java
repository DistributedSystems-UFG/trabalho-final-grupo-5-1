package br.ufg.marmitaria.estoques.modelo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot imutável do estado compartilhado. Os mapas recebidos são copiados,
 * impedindo que referências externas modifiquem o inventário fora do repositório.
 */
public record InventarioEstado(
        long versao,
        boolean lojaAberta,
        Map<String, Integer> marmitas,
        Map<String, Integer> insumos,
        Set<String> operacoesProcessadas,
        long atualizadoEmEpochMs) {

    public InventarioEstado {
        marmitas = Collections.unmodifiableMap(new LinkedHashMap<>(marmitas));
        insumos = Collections.unmodifiableMap(new LinkedHashMap<>(insumos));
        operacoesProcessadas = Collections.unmodifiableSet(new LinkedHashSet<>(operacoesProcessadas));
    }

    public static InventarioEstado vazio() {
        Map<String, Integer> marmitas = new LinkedHashMap<>();
        for (String tamanho : new String[]{"P", "M", "G"}) {
            for (String proteina : new String[]{"BOVINA", "SUINA", "FRANGO"}) {
                marmitas.put(tamanho + "_" + proteina, 0);
            }
        }

        Map<String, Integer> insumos = new LinkedHashMap<>();
        for (String item : new String[]{
                "ARROZ", "FEIJAO", "MACARRAO", "VERDURAS",
                "CARNE_BOVINA", "CARNE_SUINA", "CARNE_FRANGO",
                "EMBALAGEM_P", "EMBALAGEM_M", "EMBALAGEM_G"}) {
            insumos.put(item, 0);
        }

        return new InventarioEstado(
                0,
                true,
                marmitas,
                insumos,
                Set.of(),
                System.currentTimeMillis());
    }
}
