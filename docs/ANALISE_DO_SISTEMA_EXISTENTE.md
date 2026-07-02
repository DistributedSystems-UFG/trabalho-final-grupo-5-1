# Análise do serviço de estoques já existente

O projeto `exercicio17-io-intensive-application` é um bom ponto de partida, mas representa apenas um componente do trabalho final.

## O que já estava atendido

### Serviço remoto e múltiplos clientes

O contrato gRPC disponibiliza consultas e atualizações do estoque. Os servidores com thread por requisição e pool permitem chamadas concorrentes de clientes Java/Python.

### Acesso concorrente a arquivo compartilhado

O repositório possui uma trava de leitura/escrita:

```java
private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
```

Consultas usam `readLock`, permitindo várias leituras. Atualizações usam `writeLock`, serializando a sequência ler-modificar-gravar.

### Integridade física dos arquivos

```java
objectMapper.writeValue(temporario.toFile(), valor);
Files.move(
    temporario,
    destino,
    StandardCopyOption.ATOMIC_MOVE,
    StandardCopyOption.REPLACE_EXISTING);
```

O arquivo oficial só é substituído depois de o temporário estar completo. Isso evita JSON parcialmente escrito.

### Processamento concorrente no servidor

As versões single-threaded, thread por requisição e pool já demonstram diferentes executores e capacidade de vazão.

## Lacuna principal: atomicidade da regra de negócio

No serviço original, um cliente poderia fazer:

```text
ConsultarEstoque → decidir → AjustarEstoque
```

Duas chamadas separadas não formam uma transação. Dois vendedores podem consultar o mesmo saldo antes de qualquer baixa e ambos confirmar a venda.

A versão integrada substitui isso por:

```proto
rpc ReservarMarmitas(ReservaMarmitasRequest)
    returns (ReservaMarmitasResponse);
```

Dentro do repositório:

```java
lock.writeLock().lock();
try {
    InventarioEstado atual = lerSemLock();
    Map<String, Integer> faltantes = calcularFaltantes(atual.marmitas(), itens);
    if (!faltantes.isEmpty()) {
        return falha;
    }
    // baixa de todos os itens
    gravarAtomico(novoEstado);
} finally {
    lock.writeLock().unlock();
}
```

A verificação e a baixa ficam indivisíveis para as threads da instância.

## Por que o snapshot virou um único arquivo

O projeto original mantinha marmitas e insumos em dois JSONs. Uma operação como produção precisa alterar ambos. Se o processo falhasse entre as duas gravações, um arquivo poderia refletir a produção e o outro não.

A versão final mantém em `inventario.json`:

- marmitas prontas;
- insumos;
- estado aberto/fechado da loja;
- versão;
- IDs de operações processadas.

Assim, produção e reserva geram um único snapshot e uma única substituição atômica.

## O que foi acrescentado para o trabalho final

- servidor Ice do cardápio e cliente Python;
- pedidos concorrentes com reserva atômica;
- produção e reserva na mesma transação lógica;
- Kafka para filas e publish-subscribe;
- cozinha, comprador, workers, dashboard e replicador;
- alertas de marmitas e insumos baixos;
- idempotência para retries e reentrega de mensagens;
- réplica de leitura e failover;
- partições Kafka e particionamento funcional;
- autorização opcional por papéis.
