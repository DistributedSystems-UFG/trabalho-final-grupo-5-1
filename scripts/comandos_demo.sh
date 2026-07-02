#!/usr/bin/env bash
cat <<'EOF'
Abra terminais separados na MESMA EC2 e execute:

1) Estoque primário
cd estoque-service-java
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 mvn exec:java   -Dexec.mainClass=br.ufg.marmitaria.estoques.servidor.ServidorEstoque   -Dexec.args="50051 dados-principal primaria 16"

2) Estoque réplica
cd estoque-service-java
mvn exec:java   -Dexec.mainClass=br.ufg.marmitaria.estoques.servidor.ServidorEstoque   -Dexec.args="50054 dados-replica replica 8"

3) Replicador
source .venv/bin/activate
PYTHONPATH=clientes-python python clientes-python/workers/replicador.py

4) Cardápio Ice
cd cardapio-ice-java
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ESTOQUE_PRIMARIO=localhost:50051 ESTOQUE_REPLICA=localhost:50054 gradle run

5) Cozinha
source .venv/bin/activate
PYTHONPATH=clientes-python python clientes-python/workers/cozinha.py

6) Comprador
source .venv/bin/activate
PYTHONPATH=clientes-python python clientes-python/workers/comprador.py

7) Dois ou mais workers (mesmo grupo Kafka)
WORKER_ID=worker-1 PYTHONPATH=clientes-python python clientes-python/workers/processador_pedidos.py
WORKER_ID=worker-2 PYTHONPATH=clientes-python python clientes-python/workers/processador_pedidos.py

8) Cliente final
PYTHONPATH=clientes-python python clientes-python/cliente_cardapio.py
EOF
