# Sistema Distribuído da Marmitaria — Guia Operacional Completo na EC2

Este roteiro parte de uma instância **Ubuntu 22.04 LTS** praticamente vazia e coloca todo o sistema em execução na mesma EC2.

> Caminho adotado neste guia:
>
> ```text
> /mnt/efs/fs1/SistemaMarmitaria
> ```
>
> Caso o projeto esteja em outro local, altere apenas a variável `PROJETO` criada na seção 8.

---

## 1. Security Group da EC2

Libere:

| Porta | Origem | Uso |
|---|---|---|
| TCP 22 | seu IP | SSH |
| TCP 10000 | seu IP | cliente Ice executado fora da EC2 |

Não exponha publicamente:

- Kafka: `9092`;
- estoque primário: `50051`;
- estoque réplica: `50054`.

Essas portas podem permanecer acessíveis apenas internamente na EC2.

---

## 2. Conectar à EC2

No PowerShell do Windows:

```powershell
ssh -i "CAMINHO_DA_CHAVE.pem" ubuntu@IP_PUBLICO_DA_EC2
```

Confirme a versão do Ubuntu:

```bash
lsb_release -a
```

Este roteiro foi preparado para Ubuntu 22.04 LTS, que é a opção mais simples para utilizar Ice 3.7 e Python 3.10.

---

## 3. Instalar os pacotes básicos

```bash
sudo apt update
sudo apt install -y \
  git \
  curl \
  wget \
  unzip \
  tar \
  build-essential \
  software-properties-common \
  openjdk-17-jdk \
  maven \
  python3 \
  python3-venv \
  python3-pip
```

Confirme:

```bash
java -version
mvn -version
python3 --version
git --version
```

O Java deve ser 17 ou superior.

---

## 4. Instalar Ice e os compiladores Slice

Habilite o repositório `universe` e instale os pacotes:

```bash
sudo add-apt-repository universe -y
sudo apt update
sudo apt install -y zeroc-ice-all-dev zeroc-ice-all-runtime
```

Confirme os compiladores:

```bash
slice2java --version
slice2py --version
```

Os dois comandos precisam existir antes da compilação do cardápio e da geração do cliente Python.

Caso o `apt` informe que os pacotes não existem, confirme que a instância é Ubuntu 22.04 e que o repositório `universe` foi habilitado.

---

## 5. Instalar Gradle 7.3

O projeto ainda não contém Gradle Wrapper. Instale a versão 7.3:

```bash
cd /tmp
wget -q https://services.gradle.org/distributions/gradle-7.3-bin.zip
sudo rm -rf /opt/gradle/gradle-7.3
sudo mkdir -p /opt/gradle
sudo unzip -q gradle-7.3-bin.zip -d /opt/gradle
```

Crie a configuração global:

```bash
sudo tee /etc/profile.d/gradle.sh > /dev/null <<'EOF'
export GRADLE_HOME=/opt/gradle/gradle-7.3
export PATH=$GRADLE_HOME/bin:$PATH
EOF
```

Carregue a configuração:

```bash
sudo chmod +x /etc/profile.d/gradle.sh
source /etc/profile.d/gradle.sh
```

Confirme:

```bash
gradle -v
```

Se abrir outro terminal SSH e `gradle` não for encontrado:

```bash
source /etc/profile.d/gradle.sh
```

---

## 6. Clonar ou atualizar o projeto

### Primeira instalação

```bash
cd /mnt/efs/fs1
git clone https://github.com/EduardoBottosso/SistemaMarmitaria.git
cd /mnt/efs/fs1/SistemaMarmitaria
```

### Se a pasta já existir

```bash
cd /mnt/efs/fs1/SistemaMarmitaria
git pull
```

Confira a estrutura:

```bash
ls
```

Devem aparecer, entre outros:

```text
cardapio-ice-java
clientes-python
config
docs
estoque-service-java
infra
scripts
README.md
```

---

## 7. Instalar e iniciar o Kafka

### 7.1 Baixar o Kafka

Este guia utiliza Kafka 4.3.0 em modo KRaft, sem ZooKeeper:

```bash
cd /tmp
wget -q https://downloads.apache.org/kafka/4.3.0/kafka_2.13-4.3.0.tgz
sudo rm -rf /opt/kafka_2.13-4.3.0
sudo tar -xzf kafka_2.13-4.3.0.tgz -C /opt
sudo ln -sfn /opt/kafka_2.13-4.3.0 /opt/kafka
sudo chown -R ubuntu:ubuntu /opt/kafka_2.13-4.3.0
sudo chown -h ubuntu:ubuntu /opt/kafka
```

Confirme:

```bash
/opt/kafka/bin/kafka-topics.sh --version
```

### 7.2 Configurar um diretório persistente

```bash
sudo mkdir -p /var/lib/kafka
sudo chown -R ubuntu:ubuntu /var/lib/kafka
```

Altere o diretório dos logs do broker:

```bash
sed -i 's#^log.dirs=.*#log.dirs=/var/lib/kafka#' \
  /opt/kafka/config/server.properties
```

Confira:

```bash
grep '^log.dirs=' /opt/kafka/config/server.properties
```

Resultado esperado:

```text
log.dirs=/var/lib/kafka
```

### 7.3 Formatar o armazenamento — somente na primeira instalação

```bash
if [ ! -f /var/lib/kafka/meta.properties ]; then
  KAFKA_CLUSTER_ID="$(
    /opt/kafka/bin/kafka-storage.sh random-uuid
  )"

  /opt/kafka/bin/kafka-storage.sh format \
    --standalone \
    -t "$KAFKA_CLUSTER_ID" \
    -c /opt/kafka/config/server.properties
else
  echo "Kafka já formatado; mantendo os dados existentes."
fi
```

Não execute uma nova formatação quando o diretório já possuir `meta.properties`.

### 7.4 Criar um serviço systemd

```bash
sudo tee /etc/systemd/system/kafka.service > /dev/null <<'EOF'
[Unit]
Description=Apache Kafka
After=network.target

[Service]
Type=simple
User=ubuntu
Group=ubuntu
Environment="KAFKA_HEAP_OPTS=-Xms512m -Xmx512m"
ExecStart=/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/server.properties
ExecStop=/opt/kafka/bin/kafka-server-stop.sh
Restart=on-failure
RestartSec=5
LimitNOFILE=100000

[Install]
WantedBy=multi-user.target
EOF
```

Inicie o serviço:

```bash
sudo systemctl daemon-reload
sudo systemctl enable kafka
sudo systemctl restart kafka
```

Confira:

```bash
sudo systemctl status kafka --no-pager
```

Verifique a porta:

```bash
ss -ltn | grep 9092
```

Se a porta não aparecer:

```bash
sudo journalctl -u kafka -n 100 --no-pager
```

### 7.5 Criar os tópicos do sistema

```bash
cd /mnt/efs/fs1/SistemaMarmitaria

export KAFKA_TOPICS_BIN=/opt/kafka/bin/kafka-topics.sh
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_REPLICATION_FACTOR=1

bash infra/criar_topicos.sh
```

Liste os tópicos:

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

Devem aparecer:

```text
alertas.estoque
estoque.snapshots
itens.consumidos
pedidos.confirmados
pedidos.producao
pedidos.producao.resultado
vendas.concretizadas
```

O texto `/caminho/do/kafka/bin/kafka-topics.sh` usado anteriormente era apenas um exemplo. Nesta instalação, o caminho real é:

```text
/opt/kafka/bin/kafka-topics.sh
```

---

## 8. Criar o arquivo de ambiente dos terminais

Crie um arquivo fora do repositório:

```bash
cat > ~/.marmitaria-env <<'EOF'
export PROJETO=/mnt/efs/fs1/SistemaMarmitaria

export KAFKA_BOOTSTRAP_SERVERS=localhost:9092

export ESTOQUE_PRIMARIO=localhost:50051
export ESTOQUE_REPLICA=localhost:50054
export ESTOQUE_GRPC_PRIMARIO=localhost:50051
export ESTOQUE_GRPC_REPLICA=localhost:50054

export CARDAPIO_ICE_PROXY="Cardapio:tcp -h localhost -p 10000"
export ICE_ENDPOINT="tcp -h 0.0.0.0 -p 10000"

export TOPICO_PEDIDOS_CONFIRMADOS=pedidos.confirmados
export TOPICO_PEDIDOS_PRODUCAO=pedidos.producao
export TOPICO_RESULTADO_PRODUCAO=pedidos.producao.resultado
export TOPICO_VENDAS_CONCRETIZADAS=vendas.concretizadas
export TOPICO_ALERTAS_ESTOQUE=alertas.estoque
export TOPICO_SNAPSHOTS_ESTOQUE=estoque.snapshots
export TOPICO_ITENS_CONSUMIDOS=itens.consumidos

export TEMPO_PRODUCAO_SEGUNDOS=10
export TEMPO_PROCESSAMENTO_PEDIDO_SEGUNDOS=2
export QUANTIDADE_REPOSICAO=100

export PYTHONPATH=/mnt/efs/fs1/SistemaMarmitaria/clientes-python
EOF
```

Em cada novo terminal SSH, execute:

```bash
source ~/.marmitaria-env
cd "$PROJETO"
```

---

## 9. Preparar o ambiente Python

Na raiz do projeto:

```bash
source ~/.marmitaria-env
cd "$PROJETO"
```

Execute:

```bash
bash scripts/preparar_python.sh
```

Quando o script terminar, ative o ambiente no terminal atual:

```bash
source .venv/bin/activate
```

Confirme as bibliotecas:

```bash
python -c "import grpc, Ice, kafka; print('Python preparado com sucesso')"
```

Confirme os arquivos gerados:

```bash
find clientes-python -maxdepth 2 \
  \( -name '*_pb2.py' -o -name '*_pb2_grpc.py' -o -path '*gerado_ice*' \)
```

Sempre que abrir um novo terminal para executar um componente Python:

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate
```

---

## 10. Compilar e testar o código

### 10.1 Serviço gRPC de estoque

```bash
source ~/.marmitaria-env
cd "$PROJETO/estoque-service-java"

mvn clean test
```

Resultado esperado:

```text
BUILD SUCCESS
```

### 10.2 Servidor Ice do cardápio

```bash
source ~/.marmitaria-env
source /etc/profile.d/gradle.sh
cd "$PROJETO/cardapio-ice-java"

gradle clean build
```

Resultado esperado:

```text
BUILD SUCCESSFUL
```

### 10.3 Verificação estrutural

```bash
source ~/.marmitaria-env
cd "$PROJETO"

bash scripts/verificar_estrutura.sh
```

---

## 11. Verificação antes de iniciar os componentes

Confira o Kafka:

```bash
sudo systemctl is-active kafka
ss -ltn | grep 9092
```

Confira os tópicos:

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

Confira os builds:

```bash
test -d "$PROJETO/estoque-service-java/target" \
  && echo "Estoque compilado"

test -d "$PROJETO/cardapio-ice-java/build" \
  && echo "Cardápio compilado"
```

---

## 12. Executar a demonstração completa

Abra terminais SSH diferentes, todos conectados à mesma EC2.

Em cada terminal, comece com:

```bash
source ~/.marmitaria-env
cd "$PROJETO"
```

Nos terminais Python, execute também:

```bash
source .venv/bin/activate
```

### Terminal 1 — estoque primário

```bash
source ~/.marmitaria-env
cd "$PROJETO/estoque-service-java"

KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
mvn exec:java \
  -Dexec.mainClass=br.ufg.marmitaria.estoques.servidor.ServidorEstoque \
  -Dexec.args="50051 dados-principal primaria 16"
```

O terminal deve permanecer executando.

### Terminal 2 — estoque réplica

```bash
source ~/.marmitaria-env
cd "$PROJETO/estoque-service-java"

mvn exec:java \
  -Dexec.mainClass=br.ufg.marmitaria.estoques.servidor.ServidorEstoque \
  -Dexec.args="50054 dados-replica replica 8"
```

### Terminal 3 — replicador

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

python clientes-python/workers/replicador.py
```

### Terminal 4 — cozinha

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

python clientes-python/workers/cozinha.py
```

### Terminal 5 — comprador

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

python clientes-python/workers/comprador.py
```

### Terminal 6 — worker 1

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

WORKER_ID=worker-1 \
python clientes-python/workers/processador_pedidos.py
```

### Terminal 7 — worker 2

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

WORKER_ID=worker-2 \
python clientes-python/workers/processador_pedidos.py
```

### Terminal 8 — dashboard

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

python clientes-python/workers/dashboard.py
```

### Terminal 9 — servidor do cardápio Ice

Inicie este componente depois dos dois estoques:

```bash
source ~/.marmitaria-env
source /etc/profile.d/gradle.sh
cd "$PROJETO/cardapio-ice-java"

KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
ESTOQUE_GRPC_PRIMARIO=localhost:50051 \
ESTOQUE_GRPC_REPLICA=localhost:50054 \
ICE_ENDPOINT="tcp -h 0.0.0.0 -p 10000" \
gradle run
```

### Terminal 10 — cliente final

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

python clientes-python/cliente_cardapio.py
```

---

## 13. Confirmar que as portas estão abertas

Em outro terminal:

```bash
ss -ltn | grep -E '9092|50051|50054|10000'
```

Resultado esperado:

```text
9092   Kafka
50051  estoque primário
50054  estoque réplica
10000  cardápio Ice
```

---

## 14. Testes importantes

### 14.1 Testes JUnit

```bash
source ~/.marmitaria-env
cd "$PROJETO/estoque-service-java"

mvn test
```

### 14.2 Reservas concorrentes via gRPC

Com o estoque primário em execução:

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

python clientes-python/testes/teste_reservas_concorrentes.py \
  --endereco localhost:50051 \
  --chamadas 20 \
  --quantidade 1
```

### 14.3 Idempotência

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate

python clientes-python/testes/teste_idempotencia.py
```

### 14.4 Failover de leitura

1. Mantenha primário, réplica e replicador em execução.
2. Faça alterações no primário.
3. Aguarde o snapshot chegar à réplica.
4. Pare o primário com `Ctrl+C`.
5. Consulte pelo cardápio e observe o uso da réplica para leitura.

---

## 15. Executar o cliente a partir do Windows

No Security Group, libere TCP `10000` apenas para seu IP.

No PowerShell local, prepare o cliente Python com as mesmas dependências e configure:

```powershell
$env:CARDAPIO_ICE_PROXY="Cardapio:tcp -h IP_PUBLICO_DA_EC2 -p 10000"
```

Depois execute o cliente. Kafka e gRPC não precisam ser expostos publicamente.

---

## 16. Como parar o sistema

Nos terminais dos componentes:

```text
Ctrl+C
```

Para parar o Kafka:

```bash
sudo systemctl stop kafka
```

Para iniciá-lo novamente:

```bash
sudo systemctl start kafka
```

Para ver o estado:

```bash
sudo systemctl status kafka --no-pager
```

---

## 17. Rotina após reiniciar ou reconectar à EC2

O Kafka inicia automaticamente por estar habilitado no systemd.

Confirme:

```bash
sudo systemctl is-active kafka
```

Atualize o código:

```bash
cd /mnt/efs/fs1/SistemaMarmitaria
git pull
```

Carregue o ambiente:

```bash
source ~/.marmitaria-env
source /etc/profile.d/gradle.sh
cd "$PROJETO"
source .venv/bin/activate
```

Depois inicie novamente os componentes da seção 12.

---

## 18. Diagnóstico de erros comuns

### `/caminho/do/kafka/...: No such file or directory`

Foi utilizado um placeholder. O caminho real deste guia é:

```text
/opt/kafka/bin/kafka-topics.sh
```

### Nada aparece em `ss -ltn | grep 9092`

O Kafka não está executando:

```bash
sudo systemctl status kafka --no-pager
sudo journalctl -u kafka -n 100 --no-pager
```

### `slice2java: command not found`

```bash
sudo add-apt-repository universe -y
sudo apt update
sudo apt install -y zeroc-ice-all-dev zeroc-ice-all-runtime
```

### `slice2py: command not found`

Ative o ambiente virtual:

```bash
source /mnt/efs/fs1/SistemaMarmitaria/.venv/bin/activate
```

Depois:

```bash
python -m pip install -r \
  /mnt/efs/fs1/SistemaMarmitaria/clientes-python/requirements.txt
```

### `gradle: command not found`

```bash
source /etc/profile.d/gradle.sh
gradle -v
```

### `ModuleNotFoundError`

```bash
source ~/.marmitaria-env
cd "$PROJETO"
source .venv/bin/activate
```

### `Connection refused` em `localhost:50051`

O estoque primário ainda não está em execução. Inicie o Terminal 1.

### `Connection refused` em `localhost:9092`

O Kafka não está ativo:

```bash
sudo systemctl restart kafka
sudo systemctl status kafka --no-pager
```

### `Address already in use`

Descubra o processo:

```bash
sudo ss -ltnp | grep -E '9092|50051|50054|10000'
```

Encerre o processo antigo ou use a instância já iniciada.

### Falha ao instalar `zeroc-ice==3.7.11`

Ice 3.7.11 é mais simples em Ubuntu 22.04 com Python 3.10. Em Ubuntu 24.04/Python 3.12, pode não existir um pacote binário compatível. Use uma AMI Ubuntu 22.04 LTS ou um ambiente Python compatível.

---

## 19. Ordem mínima para o primeiro teste

Para provar o funcionamento básico sem abrir todos os componentes:

1. Kafka;
2. estoque primário;
3. estoque réplica;
4. replicador;
5. cardápio Ice;
6. cliente final.

Depois acrescente:

7. cozinha;
8. comprador;
9. workers;
10. dashboard.

---

## 20. Checklist final

```text
[ ] Java 17 instalado
[ ] Maven instalado
[ ] Gradle 7.3 instalado
[ ] slice2java disponível
[ ] slice2py disponível
[ ] Kafka ativo em localhost:9092
[ ] sete tópicos criados
[ ] ambiente Python criado
[ ] stubs gRPC gerados
[ ] stubs Ice gerados
[ ] estoque-service-java compilado
[ ] cardapio-ice-java compilado
[ ] estoque primário ativo em 50051
[ ] estoque réplica ativa em 50054
[ ] cardápio Ice ativo em 10000
[ ] workers e dashboard conectados
[ ] cliente final consegue fazer pedidos
```
