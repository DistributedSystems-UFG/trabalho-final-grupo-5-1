package br.ufg.marmitaria.estoques.modelo;

import java.util.List;
import java.util.Map;

public record ResultadoOperacao(
        boolean sucesso,
        boolean jaProcessada,
        String mensagem,
        InventarioEstado snapshot,
        List<AlertaEstoque> alertas,
        Map<String, Integer> insumosConsumidos,
        Map<String, Integer> marmitasProduzidas,
        Map<String, Integer> faltantes) {

    public static ResultadoOperacao falha(String mensagem, InventarioEstado snapshot, Map<String, Integer> faltantes) {
        return new ResultadoOperacao(false, false, mensagem, snapshot, List.of(), Map.of(), Map.of(), Map.copyOf(faltantes));
    }
}
