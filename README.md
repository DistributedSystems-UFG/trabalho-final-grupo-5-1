Grupo 5,
Integrantes: Christian Matheus de Paula, Eduardo Borges Bottosso, Gabriel Brito Ribeiro e Maikon Matheus R.

# Sistema Distribuído de Controle de Inventário de uma Marmitaria

Projeto para a disciplina **Software Concorrente e Distribuído — UFG, 2026.1**. O sistema integra Java e Python, chamadas remotas síncronas com **Ice** e **gRPC**, comunicação assíncrona com **Kafka**, processamento concorrente, persistência em arquivo, alertas, replicação e clientes simulados.

## 1. Visão geral

O cliente final faz um pedido pelo cardápio Ice. O servidor do cardápio consulta o serviço gRPC de estoque e tenta executar `ReservarMarmitas`, que verifica e deduz todas as unidades do pedido em uma única operação protegida por trava.

- Havendo estoque pronto, o pedido é confirmado e publicado em `pedidos.confirmados`.
- Faltando marmitas, mas havendo insumos, o cliente pode aceitar a espera. A cozinha consome `pedidos.producao`, simula 10 segundos e chama `ProduzirEReservar`.
- Sem insumos suficientes, o cliente pode aceitar uma oferta parcial, que é novamente reservada de forma atômica.
- Sem marmitas e sem condição de produzir, a loja é fechada.
- Estoque baixo gera alertas Kafka. A cozinha reage a marmitas baixas; o comprador reage a insumos baixos.
- O primário publica snapshots; um replicador aplica-os em uma instância gRPC somente leitura.

## 2. Componentes

| Componente | Tecnologia | Papel |
|---|---|---|
| `cardapio-ice-java` | Java + Ice | servidor cliente-servidor do cardápio |
| `estoque-service-java` | Java + gRPC | autoridade do inventário e regras atômicas |
| `clientes-python/cliente_cardapio.py` | Python + Ice | cliente final da marmitaria |
| `workers/processador_pedidos.py` | Python + Kafka | fila concorrente de processamento de pedidos |
| `workers/cozinha.py` | Python + Kafka + gRPC | produção preventiva e produção por pedido |
| `workers/comprador.py` | Python + Kafka + gRPC | reposição de insumos |
| `workers/replicador.py` | Python + Kafka + gRPC | replicação assíncrona do inventário |
| `workers/dashboard.py` | Python + Kafka | visualização de vendas, alertas e consumo |

A arquitetura detalhada está em [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md), e o vínculo com cada requisito da especificação está em [`docs/MAPEAMENTO_REQUISITOS.md`](docs/MAPEAMENTO_REQUISITOS.md).

## 3. Regra de produção

Cada marmita possui uma proteína e consome:

| Tamanho | Arroz | Feijão | Macarrão | Verduras | Proteína escolhida | Embalagem |
|---|---:|---:|---:|---:|---:|---:|
| P | 1 | 1 | 1 | 1 | 1 | 1 P |
| M | 2 | 2 | 2 | 2 | 2 | 1 M |
| G | 3 | 3 | 3 | 3 | 3 | 1 G |

O estoque pronto distingue tamanho e proteína (`P_BOVINA`, `M_FRANGO`, etc.).

## 4. Garantias de concorrência e consistência

### Operações de domínio atômicas

O cliente **não** executa `consultar` e depois `ajustar` para confirmar uma venda. Isso criaria uma corrida do tipo check-then-act. Em vez disso:

- `ReservarMarmitas`: verifica e retira todas as marmitas sob um único `writeLock`;
- `ProduzirEReservar`: verifica insumos, consome insumos, produz e reserva o pedido sob a mesma trava;
- `ReporInsumos`: aplica a compra em uma única mutação.

### Persistência segura

O estado completo fica em um único `inventario.json`. A nova versão é escrita em arquivo temporário e substitui a anterior por `ATOMIC_MOVE` quando suportado. Assim, um leitor observa o snapshot antigo ou o novo, nunca um JSON pela metade.

### Imutabilidade e confinamento

Snapshots e pedidos são `record`s com cópias defensivas. O serviço gRPC é stateless. Cada `KafkaConsumer` é confinado à thread que o criou. Variáveis temporárias de cada chamada permanecem locais.

### Idempotência

Toda mutação possui `operacao_id`. IDs já processados ficam no snapshot, evitando dupla baixa quando uma chamada ou uma mensagem é repetida.

### Primário e réplica

Somente o primário aceita escrita. A réplica recebe snapshots e serve failover de leitura. Isso evita dois escritores divergentes e deixa explícita a consistência eventual da réplica.

## 5. Pré-requisitos na EC2 Ubuntu

- Java 17+
- Maven 3.8+
- Gradle compatível com o plugin Ice
- Ice 3.7.11, incluindo `slice2java` e `slice2py`
- Python compatível com Ice 3.7.11
- Kafka acessível em `localhost:9092` ou no endereço configurado

Pacotes básicos:

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven python3 python3-venv python3-pip
```

Instale Ice 3.7.11 pelos pacotes da ZeroC ou pelo ambiente já utilizado na disciplina. Confirme:

```bash
slice2java --version
slice2py --version
```

## 6. Preparar Kafka

Defina o caminho de `kafka-topics.sh` caso ele não esteja no `PATH`:

```bash
export KAFKA_TOPICS_BIN=/caminho/do/kafka/bin/kafka-topics.sh
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
bash infra/criar_topicos.sh
```

Por padrão são criadas três partições nos tópicos de trabalho. Em um broker único, use fator de replicação 1. Em um cluster, por exemplo:

```bash
export KAFKA_REPLICATION_FACTOR=2
bash infra/criar_topicos.sh
```

## 7. Preparar Python

Na raiz:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r clientes-python/requirements.txt
python clientes-python/gerar_stubs_grpc.py
bash clientes-python/gerar_stubs_ice.sh
```

Ou:

```bash
bash scripts/preparar_python.sh
```

Ao executar scripts Python da raiz, informe:

```bash
export PYTHONPATH=clientes-python
```

## 8. Compilar

### Estoque gRPC

```bash
cd estoque-service-java
mvn clean compile
cd ..
```

### Cardápio Ice

```bash
cd cardapio-ice-java
gradle clean build
cd ..
```

## 9. Executar a demonstração

Abra terminais SSH diferentes conectados à mesma EC2.

### Terminal 1 — estoque primário

```bash
cd estoque-service-java
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 mvn exec:java \
  -Dexec.mainClass=br.ufg.marmitaria.estoques.servidor.ServidorEstoque \
  -Dexec.args="50051 dados-principal primaria 16"
```

### Terminal 2 — estoque réplica

```bash
cd estoque-service-java
mvn exec:java \
  -Dexec.mainClass=br.ufg.marmitaria.estoques.servidor.ServidorEstoque \
  -Dexec.args="50054 dados-replica replica 8"
```

### Terminal 3 — replicador

```bash
source .venv/bin/activate
export PYTHONPATH=clientes-python
python clientes-python/workers/replicador.py
```

### Terminal 4 — cardápio Ice

```bash
cd cardapio-ice-java
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
ESTOQUE_GRPC_PRIMARIO=localhost:50051 \
ESTOQUE_GRPC_REPLICA=localhost:50054 \
gradle run
```

### Terminal 5 — cozinha

```bash
source .venv/bin/activate
export PYTHONPATH=clientes-python
python clientes-python/workers/cozinha.py
```

### Terminal 6 — comprador

```bash
source .venv/bin/activate
export PYTHONPATH=clientes-python
python clientes-python/workers/comprador.py
```

### Terminais 7 e 8 — dois workers concorrentes

```bash
WORKER_ID=worker-1 PYTHONPATH=clientes-python python clientes-python/workers/processador_pedidos.py
```

```bash
WORKER_ID=worker-2 PYTHONPATH=clientes-python python clientes-python/workers/processador_pedidos.py
```

### Terminal 9 — dashboard

```bash
PYTHONPATH=clientes-python python clientes-python/workers/dashboard.py
```

### Terminal 10 — cliente final

Na própria EC2:

```bash
PYTHONPATH=clientes-python python clientes-python/cliente_cardapio.py
```

De outra máquina, abra a porta TCP 10000 no Security Group e configure:

```bash
export CARDAPIO_ICE_PROXY="Cardapio:tcp -h IP_PUBLICO_DA_EC2 -p 10000"
PYTHONPATH=clientes-python python clientes-python/cliente_cardapio.py
```

As portas gRPC e Kafka podem permanecer restritas à rede privada da aplicação.

## 10. Testes importantes

Os cenários completos estão em [`docs/CENARIOS_DE_TESTE.md`](docs/CENARIOS_DE_TESTE.md).

### Testes JUnit do repositório

```bash
cd estoque-service-java
mvn test
cd ..
```

Eles verificam: limite de vendas concorrentes, idempotência e ausência de atualização parcial quando a produção falha.

### Corrida de reservas via gRPC

```bash
PYTHONPATH=clientes-python python clientes-python/testes/teste_reservas_concorrentes.py \
  --endereco localhost:50051 --chamadas 20 --quantidade 1
```

### Idempotência

```bash
PYTHONPATH=clientes-python python clientes-python/testes/teste_idempotencia.py
```

### Validação estática incluída

```bash
bash scripts/verificar_estrutura.sh
```

## 11. Observação conceitual sobre vendas e insumos

Uma venda retira **marmitas prontas**. Os ingredientes são retirados quando a cozinha **produz** novas marmitas. O evento `vendas.concretizadas` é usado para status e análise; descontar ingredientes novamente nesse consumidor provocaria dupla baixa.
