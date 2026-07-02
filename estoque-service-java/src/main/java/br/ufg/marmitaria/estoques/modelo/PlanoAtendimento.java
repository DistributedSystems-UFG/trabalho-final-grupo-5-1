package br.ufg.marmitaria.estoques.modelo;

import java.util.Map;

public record PlanoAtendimento(
        boolean atendeImediatamente,
        boolean podeProduzirFaltantes,
        boolean deveFecharLoja,
        Map<String, Integer> faltantes,
        Map<String, Integer> ofertaParcial,
        Map<String, Integer> producaoNecessaria,
        InventarioEstado snapshot,
        String mensagem) {
}
