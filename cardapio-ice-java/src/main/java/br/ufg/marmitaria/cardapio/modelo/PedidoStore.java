package br.ufg.marmitaria.cardapio.modelo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.UnaryOperator;

/** Estado de pedidos persistido e protegido contra acessos concorrentes. */
public final class PedidoStore {
    private final Path arquivo;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public PedidoStore(Path diretorio) throws IOException {
        Files.createDirectories(diretorio);
        this.arquivo = diretorio.resolve("pedidos.json");
        if (Files.notExists(arquivo)) {
            gravarSemLock(Map.of());
        }
    }

    public void criar(PedidoInterno pedido) throws IOException {
        lock.writeLock().lock();
        try {
            Map<String, PedidoInterno> pedidos = lerSemLock();
            if (pedidos.containsKey(pedido.pedidoId())) {
                throw new IllegalArgumentException("Pedido já existe: " + pedido.pedidoId());
            }
            pedidos.put(pedido.pedidoId(), pedido);
            gravarSemLock(pedidos);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<PedidoInterno> buscar(String pedidoId) throws IOException {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(lerSemLock().get(pedidoId));
        } finally {
            lock.readLock().unlock();
        }
    }

    public PedidoInterno atualizar(String pedidoId, UnaryOperator<PedidoInterno> atualizador) throws IOException {
        lock.writeLock().lock();
        try {
            Map<String, PedidoInterno> pedidos = lerSemLock();
            PedidoInterno atual = pedidos.get(pedidoId);
            if (atual == null) {
                throw new IllegalArgumentException("Pedido não encontrado: " + pedidoId);
            }
            PedidoInterno novo = atualizador.apply(atual);
            pedidos.put(pedidoId, novo);
            gravarSemLock(pedidos);
            return novo;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Map<String, PedidoInterno> lerSemLock() throws IOException {
        try (InputStream input = Files.newInputStream(arquivo)) {
            Map<String, PedidoInterno> valor = mapper.readValue(input, new TypeReference<>() {});
            return new LinkedHashMap<>(valor);
        }
    }

    private void gravarSemLock(Map<String, PedidoInterno> pedidos) throws IOException {
        Path temporario = Files.createTempFile(arquivo.getParent(), "pedidos", ".tmp");
        boolean movido = false;
        try {
            mapper.writeValue(temporario.toFile(), pedidos);
            try {
                Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
            }
            movido = true;
        } finally {
            if (!movido) {
                Files.deleteIfExists(temporario);
            }
        }
    }
}
