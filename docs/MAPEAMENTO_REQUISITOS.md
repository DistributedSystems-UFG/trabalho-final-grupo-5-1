# Mapeamento da especificação para a implementação

A especificação exige um serviço para múltiplos clientes, componentes distribuídos coordenados, acesso concorrente a dados compartilhados, processamento concorrente no servidor, interação síncrona e assíncrona, replicação/particionamento e tratamentos de consistência/disponibilidade.

| Característica | Como é atendida | Local principal no código |
|---|---|---|
| Serviço acessível a múltiplos clientes na Internet | O cardápio expõe um objeto remoto Ice na porta 10000 e o inventário expõe gRPC nas portas 50051/50054. Na EC2, vários clientes Python podem usar o IP público. | `ServidorCardapioIce.java`, `ServidorEstoque.java`, `cliente_cardapio.py` |
| Integração de componentes distribuídos | Cardápio Ice chama o inventário gRPC; cardápio e inventário publicam eventos Kafka; cozinha, comprador, workers, dashboard e replicador consomem esses tópicos. | `CardapioI.java`, `EstoqueGrpcClient.java`, `EventoPedidoPublisher.java`, `KafkaEventoEstoquePublisher.java`, `clientes-python/workers/` |
| Acessos concorrentes a dados compartilhados | O inventário e o armazenamento de pedidos usam `ReentrantReadWriteLock`. Consultas usam `readLock`; operações de verificar-e-alterar usam uma única região de `writeLock`. | `InventarioArquivoRepository.java`, `PedidoStore.java` |
| Processamento concorrente no servidor | O gRPC usa `newFixedThreadPool`; o Ice possui pool de dispatch; Kafka divide pedidos entre workers de um mesmo consumer group. | `ServidorEstoque.java`, `ServidorCardapioIce.java`, `processador_pedidos.py` |
| Interação remota síncrona | ICE para pedido/resposta e gRPC para consulta/reserva/produção/reposição. | `Cardapio.ice`, `SistemaDeEstoques.proto` |
| Interação remota assíncrona | Kafka transporta pedidos confirmados, pedidos de produção, vendas, alertas, consumo e snapshots. | `infra/criar_topicos.sh`, publishers e workers |
| Replicação | O primário publica snapshots; o replicador aplica cada versão em uma instância gRPC somente leitura. O cardápio usa a réplica como fallback para leitura. | `KafkaEventoEstoquePublisher.java`, `replicador.py`, `EstoqueGrpcClient.java` |
| Particionamento | Funcionalidades são separadas em serviços; tópicos de pedidos têm múltiplas partições para distribuição entre workers. | Diretórios por componente e `infra/criar_topicos.sh` |
| Consistência | Reserva e produção são operações de domínio atômicas, arquivos são trocados por `ATOMIC_MOVE`, IDs tornam operações idempotentes e escritas vão somente ao primário. | `reservar`, `produzirEReservar`, `gravarAtomico`, `operacoesProcessadas` |
| Disponibilidade | Leituras do cardápio fazem failover para réplica; eventos persistem no Kafka e consumidores só confirmam offsets após sucesso. | `EstoqueGrpcClient.executarLeitura`, workers Python |
| Autorização por papel (requisito adicional do cenário) | Interceptor gRPC opcional separa sistema, compras, gerente, dono e replicador por API key. | `AutorizacaoInterceptor.java` |

## Por que `Consultar` seguido de `Ajustar` não é suficiente

Duas threads poderiam consultar o mesmo saldo e ambas concluir que há estoque. Por isso, a operação `ReservarMarmitas` faz **verificação e baixa sob o mesmo `writeLock`**. A mesma regra vale para `ProduzirEReservar`: verificar insumos, consumir insumos, produzir e reservar o pedido formam uma única transação lógica.

## Estratégias de thread safety usadas

1. **Confinamento:** o `KafkaConsumer` de cada componente pertence a uma única thread; dados temporários de uma chamada ficam em variáveis locais.
2. **Imutabilidade:** `InventarioEstado`, `PedidoInterno` e itens são `record`s com cópias defensivas de mapas/listas.
3. **Tipos encapsulados:** somente o repositório acessa o arquivo autoritativo.
4. **Sincronização:** `ReentrantReadWriteLock` protege operações compostas inevitavelmente mutáveis.
5. **Atomicidade no arquivo:** o JSON novo é gravado em temporário e substitui o anterior de uma vez.
6. **Idempotência:** cada mutação carrega `operacao_id`; uma repetição não desconta o estoque novamente.

## Limites assumidos

- A réplica é de leitura e possui consistência eventual; não há escrita multi-primary.
- O lock protege threads de uma instância. A exclusão de múltiplos escritores distribuídos é obtida arquiteturalmente: somente o primário aceita escrita.
- Em demonstração com um único broker Kafka, `replication-factor=1`; para tolerância à falha do broker, deve-se executar um cluster com fator 2 ou 3. A replicação do inventário continua sendo demonstrada no nível da aplicação.
