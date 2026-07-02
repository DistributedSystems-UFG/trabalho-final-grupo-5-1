package br.ufg.marmitaria.estoques.repositorio;

import br.ufg.marmitaria.estoques.dominio.ChaveMarmita;
import br.ufg.marmitaria.estoques.dominio.Insumo;
import br.ufg.marmitaria.estoques.dominio.TamanhoMarmita;
import br.ufg.marmitaria.estoques.dominio.TipoProteina;
import br.ufg.marmitaria.estoques.modelo.ItemPedido;
import br.ufg.marmitaria.estoques.modelo.ResultadoOperacao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class InventarioArquivoRepositoryTest {
    private static final ChaveMarmita P_BOVINA =
            new ChaveMarmita(TamanhoMarmita.P, TipoProteina.BOVINA);

    @TempDir
    Path diretorio;

    @Test
    void reservasConcorrentesNaoDevemVenderMaisDoQueOEstoque() throws Exception {
        InventarioArquivoRepository repository = new InventarioArquivoRepository(diretorio);
        repository.ajustarMarmita("seed:10", P_BOVINA, 10);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Boolean>> chamadas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int indice = i;
                chamadas.add(() -> repository.reservar(
                        "reserva-concorrente:" + indice,
                        List.of(new ItemPedido(P_BOVINA, 1))).sucesso());
            }
            List<Future<Boolean>> futuros = executor.invokeAll(chamadas);
            long sucessos = 0;
            for (Future<Boolean> futuro : futuros) {
                if (futuro.get()) {
                    sucessos++;
                }
            }

            assertEquals(10, sucessos);
            assertEquals(0, repository.consultar().marmitas().get(P_BOVINA.codigo()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repetirOperacaoIdNaoDeveDeduzirNovamente() throws Exception {
        InventarioArquivoRepository repository = new InventarioArquivoRepository(diretorio);
        repository.ajustarMarmita("seed:2", P_BOVINA, 2);

        ResultadoOperacao primeira = repository.reservar(
                "operacao-repetida",
                List.of(new ItemPedido(P_BOVINA, 1)));
        ResultadoOperacao segunda = repository.reservar(
                "operacao-repetida",
                List.of(new ItemPedido(P_BOVINA, 1)));

        assertTrue(primeira.sucesso());
        assertTrue(segunda.sucesso());
        assertTrue(segunda.jaProcessada());
        assertEquals(1, repository.consultar().marmitas().get(P_BOVINA.codigo()));
    }

    @Test
    void producaoSemInsumosNaoDeveAlterarParteDoInventario() throws Exception {
        InventarioArquivoRepository repository = new InventarioArquivoRepository(diretorio);
        repository.ajustarInsumo("seed:arroz", Insumo.ARROZ, 100);
        var antes = repository.consultar();

        ResultadoOperacao resultado = repository.produzir(
                "producao-sem-insumos",
                new ItemPedido(P_BOVINA, 10));
        var depois = repository.consultar();

        assertFalse(resultado.sucesso());
        assertEquals(antes.versao(), depois.versao());
        assertEquals(antes.marmitas(), depois.marmitas());
        assertEquals(antes.insumos(), depois.insumos());
    }
}
