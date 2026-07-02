# Cenários de demonstração e testes

## 1. Vários clientes disputando o mesmo estoque

Configure `P_BOVINA=10` e envie 20 reservas concorrentes de uma unidade:

```bash
PYTHONPATH=clientes-python python clientes-python/testes/teste_reservas_concorrentes.py   --endereco localhost:50051 --chamadas 20 --quantidade 1
```

Resultado esperado: exatamente 10 sucessos, 10 recusas e saldo final zero, nunca negativo. O teste demonstra que `ReservarMarmitas` elimina a corrida entre consultar e retirar.

## 2. Repetição da mesma mensagem

```bash
PYTHONPATH=clientes-python python clientes-python/testes/teste_idempotencia.py
```

Resultado esperado: a primeira reserva altera o estoque e a segunda retorna `ja_processada=true`, sem uma segunda baixa.

## 3. Pedido atendido imediatamente

Faça um pedido cuja combinação tenha saldo. O cardápio retorna `CONFIRMADO`, o evento entra em `pedidos.confirmados`, um worker publica `VENDA_CONCRETIZADA` e o pedido passa a `CONCLUIDO`.

## 4. Pedido que exige produção

Zere ou reduza uma combinação e mantenha insumos. O cardápio oferece espera; ao aceitar, a cozinha aguarda 10 segundos e chama `ProduzirEReservar`. Insumos, produção e reserva são aplicados em uma transação lógica.

## 5. Insumos insuficientes e oferta parcial

Reduza os insumos e deixe algumas marmitas prontas. O cliente recebe a quantidade parcial. Ao aceitar, uma nova reserva atômica verifica se a oferta ainda existe.

## 6. Loja encerrada

Sem marmitas prontas e sem insumos para produzir qualquer unidade, o serviço fecha a loja. Pedidos seguintes recebem a mensagem de encerramento.

## 7. Alertas automáticos

Após uma baixa que deixe marmitas em quantidade menor ou igual a 5, o inventário publica `MARMITA_BAIXA`; a cozinha produz um lote. Insumos no limite geram `INSUMO_BAIXO`; o comprador repõe.

## 8. Réplica e disponibilidade

1. Faça uma mutação no primário.
2. Verifique que o replicador aplica uma versão maior na réplica.
3. Encerre o primário.
4. Chame `consultarDisponibilidade` pelo cardápio.

A leitura deve continuar e indicar `veio_da_replica=true`. Escritas continuam indisponíveis, evitando divergência split-brain.

## 9. Partições e workers

Execute dois ou três `processador_pedidos.py` com `WORKER_ID` diferente. Envie vários pedidos. Cada pedido deve ser processado por apenas um membro do grupo, e a carga deve se distribuir conforme as partições.
