package br.ufg.marmitaria.estoques.repositorio;

import br.ufg.marmitaria.estoques.dominio.ChaveMarmita;
import br.ufg.marmitaria.estoques.dominio.Insumo;
import br.ufg.marmitaria.estoques.modelo.AlertaEstoque;
import br.ufg.marmitaria.estoques.modelo.InventarioEstado;
import br.ufg.marmitaria.estoques.modelo.ItemPedido;
import br.ufg.marmitaria.estoques.modelo.PlanoAtendimento;
import br.ufg.marmitaria.estoques.modelo.ResultadoOperacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Repositório thread-safe do inventário.
 *
 * Todas as operações de negócio que fazem "verificar e alterar" são executadas
 * sob um único writeLock. O estado inteiro (marmitas + insumos + idempotência)
 * fica em um único JSON, permitindo substituir o snapshot completo por uma
 * movimentação atômica do sistema de arquivos.
 */
public final class InventarioArquivoRepository {
    private static final int LIMITE_MARMITA = 5;
    private static final int LIMITE_INSUMO_COMUM = 20;
    private static final int LIMITE_EMBALAGEM = 10;
    private static final int MAX_OPERACOES_PROCESSADAS = 10_000;

    private final Path arquivoInventario;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public InventarioArquivoRepository(Path diretorioDados) throws IOException {
        Path diretorio = diretorioDados.toAbsolutePath().normalize();
        Files.createDirectories(diretorio);
        this.arquivoInventario = diretorio.resolve("inventario.json");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        inicializarSeNecessario();
    }

    public InventarioEstado consultar() throws IOException {
        lock.readLock().lock();
        try {
            return lerSemLock();
        } finally {
            lock.readLock().unlock();
        }
    }

    public PlanoAtendimento planejar(Collection<ItemPedido> itens) throws IOException {
        validarItens(itens);
        lock.readLock().lock();
        try {
            InventarioEstado estado = lerSemLock();
            Map<String, Integer> faltantes = calcularFaltantes(estado.marmitas(), itens);
            Map<String, Integer> parcial = calcularOfertaParcial(estado.marmitas(), itens);
            Map<String, Integer> producao = calcularProducaoEmLotes(faltantes, 10);
            Map<String, Integer> necessidade = calcularInsumosNecessarios(producao);
            boolean podeProduzir = faltantes.isEmpty() || possuiInsumos(estado.insumos(), necessidade);
            boolean atendeAgora = faltantes.isEmpty() && estado.lojaAberta();
            boolean nenhumPronto = estado.marmitas().values().stream().mapToInt(Integer::intValue).sum() == 0;
            boolean consegueProduzirAlgo = consegueProduzirAlgumaMarmita(estado.insumos());
            boolean deveFechar = nenhumPronto && !consegueProduzirAlgo;

            String mensagem;
            if (!estado.lojaAberta()) {
                mensagem = "A loja está fechada.";
            } else if (atendeAgora) {
                mensagem = "O pedido pode ser atendido imediatamente.";
            } else if (podeProduzir) {
                mensagem = "O pedido exige produção adicional.";
            } else if (!parcial.isEmpty()) {
                mensagem = "Não há insumos suficientes; existe uma oferta parcial.";
            } else {
                mensagem = "Não há marmitas nem insumos suficientes.";
            }

            return new PlanoAtendimento(
                    atendeAgora,
                    !faltantes.isEmpty() && podeProduzir,
                    deveFechar,
                    faltantes,
                    parcial,
                    producao,
                    estado,
                    mensagem);
        } finally {
            lock.readLock().unlock();
        }
    }

    public ResultadoOperacao reservar(String operacaoId, Collection<ItemPedido> itens) throws IOException {
        validarOperacaoId(operacaoId);
        validarItens(itens);
        lock.writeLock().lock();
        try {
            InventarioEstado atual = lerSemLock();
            if (atual.operacoesProcessadas().contains(operacaoId)) {
                return sucessoIdempotente(atual, "A reserva já havia sido processada.");
            }
            if (!atual.lojaAberta()) {
                return ResultadoOperacao.falha("A loja está fechada.", atual, Map.of());
            }

            Map<String, Integer> faltantes = calcularFaltantes(atual.marmitas(), itens);
            if (!faltantes.isEmpty()) {
                return ResultadoOperacao.falha("Estoque de marmitas insuficiente.", atual, faltantes);
            }

            Map<String, Integer> novasMarmitas = new LinkedHashMap<>(atual.marmitas());
            for (ItemPedido item : itens) {
                String chave = item.chave().codigo();
                novasMarmitas.put(chave, novasMarmitas.get(chave) - item.quantidade());
            }

            InventarioEstado novo = novoEstado(atual, atual.lojaAberta(), novasMarmitas, atual.insumos(), operacaoId);
            gravarAtomico(novo);
            return sucesso("Marmitas reservadas atomicamente.", novo, Map.of(), Map.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ResultadoOperacao produzir(String operacaoId, ItemPedido item) throws IOException {
        validarOperacaoId(operacaoId);
        validarItens(List.of(item));
        lock.writeLock().lock();
        try {
            InventarioEstado atual = lerSemLock();
            if (atual.operacoesProcessadas().contains(operacaoId)) {
                return sucessoIdempotente(atual, "A produção já havia sido processada.");
            }

            Map<String, Integer> produzidas = Map.of(item.chave().codigo(), item.quantidade());
            return produzirSemLock(atual, operacaoId, produzidas, List.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ResultadoOperacao produzirEReservar(
            String operacaoId,
            Collection<ItemPedido> itensPedido,
            int tamanhoLote) throws IOException {
        validarOperacaoId(operacaoId);
        validarItens(itensPedido);
        if (tamanhoLote <= 0) {
            tamanhoLote = 10;
        }

        lock.writeLock().lock();
        try {
            InventarioEstado atual = lerSemLock();
            if (atual.operacoesProcessadas().contains(operacaoId)) {
                return sucessoIdempotente(atual, "A produção/reserva já havia sido processada.");
            }
            if (!atual.lojaAberta()) {
                return ResultadoOperacao.falha("A loja está fechada.", atual, Map.of());
            }

            Map<String, Integer> faltantes = calcularFaltantes(atual.marmitas(), itensPedido);
            Map<String, Integer> producao = calcularProducaoEmLotes(faltantes, tamanhoLote);
            Map<String, Integer> necessidade = calcularInsumosNecessarios(producao);
            Map<String, Integer> faltaInsumo = calcularFaltantesInsumos(atual.insumos(), necessidade);
            if (!faltaInsumo.isEmpty()) {
                return ResultadoOperacao.falha(
                        "Não há insumos suficientes para produzir e reservar o pedido.",
                        atual,
                        faltaInsumo);
            }

            Map<String, Integer> novasMarmitas = new LinkedHashMap<>(atual.marmitas());
            Map<String, Integer> novosInsumos = new LinkedHashMap<>(atual.insumos());

            for (Map.Entry<String, Integer> e : necessidade.entrySet()) {
                novosInsumos.put(e.getKey(), novosInsumos.get(e.getKey()) - e.getValue());
            }
            for (Map.Entry<String, Integer> e : producao.entrySet()) {
                novasMarmitas.put(e.getKey(), novasMarmitas.get(e.getKey()) + e.getValue());
            }
            for (ItemPedido item : itensPedido) {
                String chave = item.chave().codigo();
                novasMarmitas.put(chave, novasMarmitas.get(chave) - item.quantidade());
            }

            InventarioEstado novo = novoEstado(atual, true, novasMarmitas, novosInsumos, operacaoId);
            gravarAtomico(novo);
            return sucesso("Marmitas produzidas e pedido reservado em uma única transação.", novo, necessidade, producao);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ResultadoOperacao reporInsumos(String operacaoId, Map<Insumo, Integer> reposicoes) throws IOException {
        validarOperacaoId(operacaoId);
        Objects.requireNonNull(reposicoes, "reposicoes");
        if (reposicoes.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um insumo.");
        }
        lock.writeLock().lock();
        try {
            InventarioEstado atual = lerSemLock();
            if (atual.operacoesProcessadas().contains(operacaoId)) {
                return sucessoIdempotente(atual, "A reposição já havia sido processada.");
            }
            Map<String, Integer> novos = new LinkedHashMap<>(atual.insumos());
            for (Map.Entry<Insumo, Integer> e : reposicoes.entrySet()) {
                if (e.getValue() <= 0) {
                    throw new IllegalArgumentException("Reposições devem ser positivas.");
                }
                novos.put(e.getKey().name(), somarExato(novos.get(e.getKey().name()), e.getValue()));
            }
            InventarioEstado novo = novoEstado(atual, true, atual.marmitas(), novos, operacaoId);
            gravarAtomico(novo);
            return sucesso("Insumos repostos.", novo, Map.of(), Map.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ResultadoOperacao ajustarMarmita(
            String operacaoId,
            ChaveMarmita chave,
            int variacao) throws IOException {
        validarOperacaoId(operacaoId);
        lock.writeLock().lock();
        try {
            InventarioEstado atual = lerSemLock();
            if (atual.operacoesProcessadas().contains(operacaoId)) {
                return sucessoIdempotente(atual, "O ajuste já havia sido processado.");
            }
            Map<String, Integer> marmitas = new LinkedHashMap<>(atual.marmitas());
            int novoValor = somarExato(marmitas.get(chave.codigo()), variacao);
            validarNaoNegativo(novoValor, "O estoque de marmitas não pode ficar negativo.");
            marmitas.put(chave.codigo(), novoValor);
            InventarioEstado novo = novoEstado(atual, atual.lojaAberta(), marmitas, atual.insumos(), operacaoId);
            gravarAtomico(novo);
            return sucesso("Estoque de marmitas ajustado.", novo, Map.of(), Map.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ResultadoOperacao ajustarInsumo(
            String operacaoId,
            Insumo insumo,
            int variacao) throws IOException {
        validarOperacaoId(operacaoId);
        lock.writeLock().lock();
        try {
            InventarioEstado atual = lerSemLock();
            if (atual.operacoesProcessadas().contains(operacaoId)) {
                return sucessoIdempotente(atual, "O ajuste já havia sido processado.");
            }
            Map<String, Integer> insumos = new LinkedHashMap<>(atual.insumos());
            int novoValor = somarExato(insumos.get(insumo.name()), variacao);
            validarNaoNegativo(novoValor, "O estoque de insumos não pode ficar negativo.");
            insumos.put(insumo.name(), novoValor);
            InventarioEstado novo = novoEstado(atual, atual.lojaAberta(), atual.marmitas(), insumos, operacaoId);
            gravarAtomico(novo);
            return sucesso("Estoque de insumos ajustado.", novo, Map.of(), Map.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ResultadoOperacao alterarEstadoLoja(String operacaoId, boolean aberta) throws IOException {
        validarOperacaoId(operacaoId);
        lock.writeLock().lock();
        try {
            InventarioEstado atual = lerSemLock();
            if (atual.operacoesProcessadas().contains(operacaoId)) {
                return sucessoIdempotente(atual, "A alteração já havia sido processada.");
            }
            InventarioEstado novo = novoEstado(atual, aberta, atual.marmitas(), atual.insumos(), operacaoId);
            gravarAtomico(novo);
            return sucesso(aberta ? "Loja aberta." : "Loja fechada.", novo, Map.of(), Map.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ResultadoOperacao aplicarSnapshotReplica(String operacaoId, InventarioEstado recebido) throws IOException {
        validarOperacaoId(operacaoId);
        Objects.requireNonNull(recebido, "recebido");
        lock.writeLock().lock();
        try {
            InventarioEstado atual = lerSemLock();
            if (recebido.versao() <= atual.versao()) {
                return sucessoIdempotente(atual, "A réplica já possui uma versão igual ou mais nova.");
            }
            gravarAtomico(recebido);
            return sucesso("Snapshot aplicado na réplica.", recebido, Map.of(), Map.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ResultadoOperacao produzirSemLock(
            InventarioEstado atual,
            String operacaoId,
            Map<String, Integer> producao,
            Collection<ItemPedido> reservas) throws IOException {
        Map<String, Integer> necessidade = calcularInsumosNecessarios(producao);
        Map<String, Integer> faltaInsumo = calcularFaltantesInsumos(atual.insumos(), necessidade);
        if (!faltaInsumo.isEmpty()) {
            return ResultadoOperacao.falha("Insumos insuficientes para a produção.", atual, faltaInsumo);
        }

        Map<String, Integer> novasMarmitas = new LinkedHashMap<>(atual.marmitas());
        Map<String, Integer> novosInsumos = new LinkedHashMap<>(atual.insumos());
        necessidade.forEach((k, v) -> novosInsumos.put(k, novosInsumos.get(k) - v));
        producao.forEach((k, v) -> novasMarmitas.put(k, novasMarmitas.get(k) + v));
        for (ItemPedido item : reservas) {
            novasMarmitas.put(item.chave().codigo(), novasMarmitas.get(item.chave().codigo()) - item.quantidade());
        }

        InventarioEstado novo = novoEstado(atual, atual.lojaAberta(), novasMarmitas, novosInsumos, operacaoId);
        gravarAtomico(novo);
        return sucesso("Produção concluída.", novo, necessidade, producao);
    }

    private ResultadoOperacao sucesso(
            String mensagem,
            InventarioEstado estado,
            Map<String, Integer> insumosConsumidos,
            Map<String, Integer> marmitasProduzidas) {
        return new ResultadoOperacao(
                true,
                false,
                mensagem,
                estado,
                detectarAlertas(estado),
                Map.copyOf(insumosConsumidos),
                Map.copyOf(marmitasProduzidas),
                Map.of());
    }

    private ResultadoOperacao sucessoIdempotente(InventarioEstado estado, String mensagem) {
        return new ResultadoOperacao(true, true, mensagem, estado, detectarAlertas(estado), Map.of(), Map.of(), Map.of());
    }

    private InventarioEstado novoEstado(
            InventarioEstado atual,
            boolean lojaAberta,
            Map<String, Integer> marmitas,
            Map<String, Integer> insumos,
            String operacaoId) {
        LinkedHashSet<String> operacoes = new LinkedHashSet<>(atual.operacoesProcessadas());
        operacoes.add(operacaoId);
        while (operacoes.size() > MAX_OPERACOES_PROCESSADAS) {
            operacoes.remove(operacoes.iterator().next());
        }
        return new InventarioEstado(
                atual.versao() + 1,
                lojaAberta,
                marmitas,
                insumos,
                operacoes,
                System.currentTimeMillis());
    }

    private static Map<String, Integer> calcularFaltantes(
            Map<String, Integer> estoque,
            Collection<ItemPedido> itens) {
        Map<String, Integer> solicitados = agruparItens(itens);
        Map<String, Integer> faltantes = new LinkedHashMap<>();
        solicitados.forEach((chave, quantidade) -> {
            int disponivel = estoque.getOrDefault(chave, 0);
            if (disponivel < quantidade) {
                faltantes.put(chave, quantidade - disponivel);
            }
        });
        return faltantes;
    }

    private static Map<String, Integer> calcularOfertaParcial(
            Map<String, Integer> estoque,
            Collection<ItemPedido> itens) {
        Map<String, Integer> solicitados = agruparItens(itens);
        Map<String, Integer> parcial = new LinkedHashMap<>();
        solicitados.forEach((chave, quantidade) -> {
            int disponivel = Math.min(quantidade, estoque.getOrDefault(chave, 0));
            if (disponivel > 0) {
                parcial.put(chave, disponivel);
            }
        });
        return parcial;
    }

    private static Map<String, Integer> calcularProducaoEmLotes(
            Map<String, Integer> faltantes,
            int tamanhoLote) {
        Map<String, Integer> producao = new LinkedHashMap<>();
        faltantes.forEach((chave, falta) -> {
            // Mantém um lote de segurança: falta 1..9 -> 10; falta 10 -> 20.
            int quantidade = ((falta / tamanhoLote) + 1) * tamanhoLote;
            producao.put(chave, quantidade);
        });
        return producao;
    }

    private static Map<String, Integer> calcularInsumosNecessarios(Map<String, Integer> producao) {
        Map<String, Integer> necessidade = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : producao.entrySet()) {
            ChaveMarmita chave = ChaveMarmita.deCodigo(e.getKey());
            int quantidade = e.getValue();
            int porcoes = Math.multiplyExact(chave.tamanho().porcoesBase(), quantidade);
            acumular(necessidade, "ARROZ", porcoes);
            acumular(necessidade, "FEIJAO", porcoes);
            acumular(necessidade, "MACARRAO", porcoes);
            acumular(necessidade, "VERDURAS", porcoes);
            acumular(necessidade, chave.proteina().insumo(), porcoes);
            acumular(necessidade, chave.tamanho().embalagem(), quantidade);
        }
        return necessidade;
    }

    private static Map<String, Integer> calcularFaltantesInsumos(
            Map<String, Integer> estoque,
            Map<String, Integer> necessidade) {
        Map<String, Integer> faltantes = new LinkedHashMap<>();
        necessidade.forEach((item, qtd) -> {
            int disponivel = estoque.getOrDefault(item, 0);
            if (disponivel < qtd) {
                faltantes.put(item, qtd - disponivel);
            }
        });
        return faltantes;
    }

    private static boolean possuiInsumos(Map<String, Integer> estoque, Map<String, Integer> necessidade) {
        return calcularFaltantesInsumos(estoque, necessidade).isEmpty();
    }

    private static boolean consegueProduzirAlgumaMarmita(Map<String, Integer> insumos) {
        for (String tamanho : new String[]{"P", "M", "G"}) {
            for (String proteina : new String[]{"BOVINA", "SUINA", "FRANGO"}) {
                Map<String, Integer> uma = Map.of(tamanho + "_" + proteina, 1);
                if (possuiInsumos(insumos, calcularInsumosNecessarios(uma))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Integer> agruparItens(Collection<ItemPedido> itens) {
        Map<String, Integer> resultado = new LinkedHashMap<>();
        for (ItemPedido item : itens) {
            acumular(resultado, item.chave().codigo(), item.quantidade());
        }
        return resultado;
    }

    private List<AlertaEstoque> detectarAlertas(InventarioEstado estado) {
        List<AlertaEstoque> alertas = new ArrayList<>();
        estado.marmitas().forEach((item, qtd) -> {
            if (qtd <= LIMITE_MARMITA) {
                alertas.add(new AlertaEstoque(
                        "MARMITA_BAIXA:" + item + ":" + estado.versao(),
                        "MARMITA_BAIXA",
                        item,
                        qtd,
                        LIMITE_MARMITA,
                        estado.versao(),
                        "Estoque baixo de marmita " + item));
            }
        });
        estado.insumos().forEach((item, qtd) -> {
            int limite = item.startsWith("EMBALAGEM_") ? LIMITE_EMBALAGEM : LIMITE_INSUMO_COMUM;
            if (qtd <= limite) {
                alertas.add(new AlertaEstoque(
                        "INSUMO_BAIXO:" + item + ":" + estado.versao(),
                        "INSUMO_BAIXO",
                        item,
                        qtd,
                        limite,
                        estado.versao(),
                        "Estoque baixo do insumo " + item));
            }
        });
        if (!estado.lojaAberta()) {
            alertas.add(new AlertaEstoque(
                    "LOJA_FECHADA:" + estado.versao(),
                    "LOJA_FECHADA",
                    "LOJA",
                    0,
                    0,
                    estado.versao(),
                    "A loja foi fechada."));
        }
        return List.copyOf(alertas);
    }

    private void inicializarSeNecessario() throws IOException {
        if (Files.notExists(arquivoInventario)) {
            gravarAtomico(InventarioEstado.vazio());
        }
    }

    private InventarioEstado lerSemLock() throws IOException {
        try (InputStream input = Files.newInputStream(arquivoInventario)) {
            return mapper.readValue(input, InventarioEstado.class);
        }
    }

    private void gravarAtomico(InventarioEstado valor) throws IOException {
        Path temporario = Files.createTempFile(
                arquivoInventario.getParent(),
                arquivoInventario.getFileName().toString(),
                ".tmp");
        boolean movido = false;
        try {
            mapper.writeValue(temporario.toFile(), valor);
            try {
                Files.move(
                        temporario,
                        arquivoInventario,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporario, arquivoInventario, StandardCopyOption.REPLACE_EXISTING);
            }
            movido = true;
        } finally {
            if (!movido) {
                Files.deleteIfExists(temporario);
            }
        }
    }

    private static void validarItens(Collection<ItemPedido> itens) {
        Objects.requireNonNull(itens, "itens");
        if (itens.isEmpty()) {
            throw new IllegalArgumentException("O pedido deve conter ao menos um item.");
        }
        for (ItemPedido item : itens) {
            Objects.requireNonNull(item, "item");
        }
    }

    private static void validarOperacaoId(String operacaoId) {
        if (operacaoId == null || operacaoId.isBlank()) {
            throw new IllegalArgumentException("operacao_id é obrigatório para garantir idempotência.");
        }
    }

    private static int somarExato(int a, int b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("A operação excedeu o limite de inteiro.", e);
        }
    }

    private static void acumular(Map<String, Integer> mapa, String chave, int valor) {
        mapa.merge(chave, valor, InventarioArquivoRepository::somarExato);
    }

    private static void validarNaoNegativo(int valor, String mensagem) {
        if (valor < 0) {
            throw new IllegalArgumentException(mensagem);
        }
    }
}
