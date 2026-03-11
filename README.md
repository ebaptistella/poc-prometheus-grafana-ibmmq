# POC: IBM MQ + Prometheus + Grafana

Ambiente local que simula **IBM MQ** com coleta de métricas via **Prometheus** e visualização no **Grafana**.

## Arquitetura

```
┌─────────────┐     scrape      ┌────────────┐     query      ┌─────────┐
│   IBM MQ    │ ───────────────► │ Prometheus │ ◄───────────── │ Grafana │
│ :1414, :9157│   /metrics      │   :9090    │   datasource   │  :3000   │
└─────────────┘                 └────────────┘                └─────────┘
```

- **IBM MQ** (imagem oficial `icr.io/ibm-messaging/mq:9.4.x`): queue manager com endpoint de métricas Prometheus na porta **9157** (`MQ_ENABLE_METRICS=true`).
- **mq_prometheus** ([ibm-messaging/mq-metric-samples](https://github.com/ibm-messaging/mq-metric-samples)): collector que conecta ao MQ como cliente e expõe métricas **por fila** (profundidade, handles) e **por canal** na porta **9158**. Build a partir do repositório GitHub; config em `mq-prometheus/mq_prometheus.yaml`.
- **Prometheus**: coleta métricas do MQ (9157) e do mq_prometheus (9158); armazena séries temporais.
- **Grafana**: datasource Prometheus já provisionado; login `admin` / `admin`.

## Pré-requisitos

- Docker e Docker Compose
- Para IBM MQ 9.4+, é necessário aceitar a licença (variável `LICENSE=accept` já está no compose) e fornecer senhas via **secrets**

## Uso rápido

### 1. Criar os secrets (obrigatório na primeira vez)

O IBM MQ 9.4 exige senhas via Docker secrets. Crie os arquivos:

```bash
make secrets
# ou manualmente:
mkdir -p secrets
printf '%s' 'passw0rd' > secrets/mqAdminPassword
printf '%s' 'passw0rd' > secrets/mqAppPassword
```

### 2. Subir o ambiente

Na primeira vez (ou ao alterar a versão do collector), construa a imagem do **mq_prometheus** (build a partir do repositório mq-metric-samples; pode levar alguns minutos):

```bash
docker compose build mq_prometheus
```

Depois suba a pilha:

```bash
docker compose up -d
```

Aguarde o healthcheck do MQ (~30s). O mq_prometheus usa o usuário **admin** (canal DEV.ADMIN.SVRCONN) para acessar filas de sistema e publicações; a senha está em `mq-prometheus/mq_prometheus.yaml`. Se alterar `secrets/mqAdminPassword`, atualize o campo `password` no YAML. Depois:

- **Grafana**: http://localhost:3000 (admin / admin)
- **Prometheus**: http://localhost:9090
- **IBM MQ Web Console**: https://localhost:9443/ibmmq/console (admin / passw0rd; aceitar certificado)
- **Métricas MQ (raw)**: http://localhost:9157/metrics

### 3. Verificar métricas do MQ

```bash
make metrics
# ou
curl -s http://localhost:9157/metrics | head -100
```

No Prometheus (http://localhost:9090), use **Explore** e filtre por `ibmmq_` para ver as métricas do queue manager.

### 4. Dashboard IBM MQ no Grafana

Um dashboard com as métricas do IBM MQ é **carregado automaticamente** via provisioning:

1. Acesse http://localhost:3000 e faça login (admin / admin).
2. No menu lateral: **Dashboards** → pasta **IBM MQ** → **IBM MQ – Métricas do Queue Manager**.

O dashboard inclui:

- **Visão geral**: CPU (1/5/15 min), RAM livre, CPU user/system, uso de RAM do QM.
- **Operações MQI**: taxa de Put/Get, commits/rollbacks, conexões e MQOPEN/MQCLOSE.
- **Mensagens**: throughput em bytes, mensagens persistentes/não persistentes, pub/sub.
- **Sistema de arquivos e log**: espaço livre em disco (QM, log, errors), latência do log, uso em bytes.
- **Falhas e erros**: failed MQPUT/MQGET/MQCONN/MQOPEN, mensagens expiradas, FDC.
- **Outras MQI**: MQCTL, MQINQ, MQSET, MQCB, browse e purge.
- **Canais e conexões**: conexões ativas ao QM, taxa de conexões/desconexões, MQOPEN/MQCLOSE; o **mq_prometheus** expõe métricas por canal (ex.: `ibmmq_chl_status`).
- **Lag / profundidade das filas**: gráficos do **mq_prometheus** — profundidade atual e máxima por fila (`ibmmq_q_depth`, `ibmmq_q_attribute_max_depth`), uso da capacidade (%), handles de leitura/escrita, contagem de filas monitoradas.

Refresh padrão: 30s.

### Como monitorar canal desconectado

O endpoint nativo do container MQ (porta 9157) **não** expõe status por canal (Running / Inactive / Stopped / Retrying). Para ter a mesma visão da MQ Console (contagem por status e lista de canais com estado):

1. **Use o exporter mq_prometheus** do repositório [mq-metric-samples](https://github.com/ibm-messaging/mq-metric-samples): ele conecta ao MQ como cliente, coleta métricas por canal e expõe em HTTP para o Prometheus (ex.: porta 9158).
2. **Configure o Prometheus** para fazer scrape desse exporter (novo job apontando para a porta do mq_prometheus).
3. **No Grafana:**
   - **Painel de canais desconectados:** use a métrica exposta por canal (ex.: `ibmmq_chl_status` ou equivalente, com labels como `channel`, `status` ou `status_squash`). Canais desconectados = status diferente de RUNNING (ex.: Inactive, Stopped, Retrying). Exemplo de query: contar canais onde status não é RUNNING.
   - **Alerta:** crie um alerta no Prometheus ou Grafana quando a contagem de canais “não running” for &gt; 0 (ex.: `count(ibmmq_chl_status) unless (status_squash=="RUNNING")` ou conforme os nomes reais das métricas do exporter).

Com isso você consegue monitorar “tenho canal desconectado” da mesma forma que na tela da MQ Console (Inactive, Stopped, Retrying). ### Alertas (Prometheus)

O Prometheus está configurado com **regras de alerta** baseadas nas métricas do IBM MQ. Os alertas são avaliados a cada 30s; quando a condição é satisfeita por mais do que o `for` da regra, o alerta fica em **Firing**.

**Onde ver:** Prometheus → **Status** → **Alerts** (http://localhost:9090/alerts). Alertas em Firing aparecem em vermelho.

**Regras incluídas** (`prometheus/alerts/ibmmq.yml`): **IBMMQDown** (QM inacessível), **MQPrometheusDown** (collector mq_prometheus inacessível), **IBMMQHighCPU**, **IBMMQLowRAM**, **IBMMQLowDiskQM**, **IBMMQLowDiskLog**, **IBMMQFailedConnections**, **IBMMQFailedPut**, **IBMMQFailedGet**, **IBMMQLogWriteLatencyHigh**, **IBMMQQueueDepthHigh**, **IBMMQQueueDepthCritical**, **IBMMQQueueDepthNearMax**. Para notificações (Slack, email), adicione o Alertmanager. No Grafana: **Edit** no painel → **Alert** → **Create alert rule** com a métrica desejada.

**Lag das filas e alertas:** esta POC inclui o **mq_prometheus** (mq-metric-samples), que expõe `ibmmq_q_depth` e `ibmmq_q_attribute_max_depth` por fila. O Prometheus faz scrape em `mq_prometheus:9158`. Os alertas **IBMMQQueueDepthHigh** (depth > 500 por 5 min), **IBMMQQueueDepthCritical** (depth > 5000 por 2 min) e **IBMMQQueueDepthNearMax** (profundidade > 80% do máximo) disparam quando o lag fica alto.

**Onde visualizar os alertas de lag:**
1. **Grafana** — No dashboard **IBM MQ – Métricas do Queue Manager**, na seção **Lag / profundidade das filas**, o painel **"Alertas de lag (firing)"** mostra em tempo real quais alertas estão em firing.
2. **Prometheus** — **Status → Alerts** (http://localhost:9090/alerts): lista todos os alertas (Pending e Firing) com fila, valor e descrição.

Ajuste os limites em `prometheus/alerts/ibmmq.yml` (grupo `ibmmq_queue_lag`) conforme sua necessidade.

### Painéis com "No data" (filas/canais) — troubleshooting

**Você não perdeu informações.** O mq_prometheus (mq-metric-samples) expõe **o mesmo ou mais** que o exporter antigo: profundidade por fila (`ibmmq_q_depth`), capacidade máxima (`ibmmq_q_attribute_max_depth`), handles de leitura/escrita (`ibmmq_q_input_handles`, `ibmmq_q_output_handles`), idade da mensagem mais antiga (`ibmmq_q_oldest_message_age`), **além de** status de canais (`ibmmq_chl_status`, `ibmmq_chl_status_squash`). O "No data" indica falha na **coleta ou configuração**, não ausência de métricas no exporter.

Para diagnosticar:

1. **Collector mq_prometheus está UP?** No topo do dashboard, o painel "mq_prometheus (métricas por fila)" deve mostrar UP (verde). Se mostrar DOWN, o container não está respondendo na porta 9158.
2. **Métricas do collector no host:**  
   `curl -s http://localhost:9158/metrics | grep -E "^ibmmq_q_|^ibmmq_chl_" | head -30`  
   Se retornar linhas com `ibmmq_q_depth`, `ibmmq_chl_status_squash`, etc., o collector está expondo dados; o problema pode ser o Prometheus não estar fazendo scrape ou o Grafana usando outro datasource/intervalo.
3. **Prometheus está raspando?** Em http://localhost:9090/targets verifique se o target `mq_prometheus` (job `mq_prometheus`) está **UP** e se há erros de scrape.
4. **Logs do mq_prometheus:**  
   `docker compose logs mq_prometheus`  
   Procure por "Connected to queue manager", erros de conexão (host/porta, usuário/senha) ou falha ao descobrir filas. O host do QM deve ser o nome do serviço Docker: `ibmmq` (já configurado em `connName: ibmmq(1414)` em `mq-prometheus/mq_prometheus.yaml`).
5. **Build da imagem:** A primeira vez exige `docker compose build mq_prometheus` (build a partir do GitHub; pode levar vários minutos). Se o build falhar, os painéis de fila/canal ficarão sem dados.
6. **MQRC_NOT_AUTHORIZED (2035) ao abrir SYSTEM.ADMIN.COMMAND.QUEUE:** o collector precisa de um usuário com permissão em filas de sistema (ex.: **admin**). Em `mq-prometheus/mq_prometheus.yaml` use `channel: DEV.ADMIN.SVRCONN` e `user: admin` (senha igual à do secret mqAdminPassword).
7. **Endpoint OK mas nenhuma métrica ibmmq_q_* / ibmmq_chl_*:** não é bug do exporter. O mq_prometheus preenche essas métricas quando (a) recebe publicações do QM nos tópicos $SYS e (b) consegue executar DISPLAY QSTATUS/CHSTATUS (object status). Aguarde **1–2 minutos** após o container subir (a primeira leva de publicações pode demorar). Se continuar vazio: em `mq_prometheus.yaml` ponha `logLevel: DEBUG`, reinicie o container e rode `docker compose logs mq_prometheus` — procure por erros de subscrição, "Connected to queue manager", e mensagens de descoberta de filas/canais.

## Comandos úteis

| Comando     | Descrição                    |
|------------|------------------------------|
| **`make demo`** | **Faz tudo:** cria secrets, build do app, sobe IBM MQ + Prometheus + Grafana + **producer em loop** (5 msgs a cada 20s) + **consumer em loop**. Produz e consome enquanto estiver no ar. |
| `make up`  | Sobe só a pilha (MQ, Prometheus, Grafana), sem producer/consumer contínuos |
| `make down`| Para e remove todos os containers (inclui os do demo) |
| `make logs`| Acompanha os logs            |
| `make status` | Lista o status dos serviços |
| `make metrics` | Mostra amostra do endpoint /metrics do MQ (porta 9157) |
| `make metrics-mq-prometheus` | Mostra amostra das métricas do mq_prometheus (porta 9158; filas/canais) — útil se os painéis de fila/canal mostrarem "No data" |

## Producer e Consumer (fila DEV.QUEUE.1)

Há uma aplicação Java (JMS) que envia e consome mensagens na fila **DEV.QUEUE.1**:

- **Producer**: envia mensagens de texto para a fila.
- **Consumer**: consome mensagens da fila (encerra após alguns segundos sem nova mensagem).

Com o MQ no ar (`docker compose up -d`), em um terminal rode o consumer e em outro o producer:

```bash
cd app
# Terminal 1 – consumer (espera mensagens)
mvn exec:java -Dexec.mainClass="com.poc.mq.Consumer"

# Terminal 2 – producer (envia 5 mensagens)
mvn exec:java -Dexec.mainClass="com.poc.mq.Producer"
```

Configuração via variáveis de ambiente: `MQ_HOST`, `MQ_PORT`, `MQ_QUEUE`, `MQ_USER`, `MQ_PASSWORD`, etc. (defaults: localhost:1414, DEV.QUEUE.1, app/passw0rd). Ver `app/README.md`.

### Por que a profundidade da fila (lag) cresce no demo?

No **demo** (`make demo`), o **producer-loop** envia **100 mensagens** por janela e espera **3–15 s** entre janelas; o **consumer-loop** consome **uma mensagem por vez** e, após 25 s sem mensagem, encerra e espera 2 s antes de reconectar. Ou seja:

- **Producer:** ~100 msgs a cada ~9 s em média → picos de 100 mensagens.
- **Consumer:** 1 consumer, 1 mensagem por `receive()`; entre ciclos há 2 s de pausa.

Com isso o producer tende a colocar mensagens na fila mais rápido do que um único consumer consegue drenar. **Profundidade em 5k (ou mais) é esperada** com o demo rodando por um tempo — producer e consumer estão operando corretamente; o desbalanceamento é de throughput.

**Para reduzir o lag:**

1. **Só drenar (sem produzir):** pare o producer (`docker compose stop producer-loop`) e deixe só o consumer rodando até a fila baixar.
2. **Equilibrar o demo:** reduza o batch ou aumente o intervalo do producer (ex.: `Producer random 30 10 20` = 30 msgs a cada 10–20 s) ou rode mais de um consumer (vários `consumer-loop` ou várias instâncias manuais).
3. **Limpar a fila:** na MQ Console (https://localhost:9443/ibmmq/console), na fila DEV.QUEUE.1 use **Clear** (purge) se quiser zerar o backlog.

## Estrutura do projeto

```
.
├── app/                     # Producer e consumer Java (JMS)
│   ├── pom.xml
│   ├── README.md
│   └── src/main/java/com/poc/mq/
│       ├── MqConfig.java
│       ├── Producer.java
│       └── Consumer.java
├── docker-compose.yml
├── mq-prometheus/            # mq_prometheus (ibm-messaging/mq-metric-samples): mq_prometheus.yaml (filas, QM, canal, porta 9158)
├── prometheus/
│   ├── prometheus.yml       # scrape e rule_files
│   └── alerts/
│       └── ibmmq.yml        # regras de alerta (CPU, disco, falhas MQI, etc.)
├── grafana/
│   └── provisioning/
│       ├── datasources/
│       │   └── datasources.yml
│       └── dashboards/
│           ├── dashboards.yml    # provider que carrega a pasta default/
│           └── default/
│               └── ibmmq-metrics.json   # dashboard IBM MQ
├── secrets/                 # mqAdminPassword, mqAppPassword (não versionados)
├── Makefile
└── README.md
```

## Métricas do IBM MQ (exemplos)

Com `MQ_ENABLE_METRICS=true`, o container expõe métricas no formato Prometheus, por exemplo:

- `ibmmq_qmgr_*` – queue manager (commits, CPU, chamadas MQI, etc.)
- Outras métricas de sistema publicadas pelo MQ (consulte a [documentação IBM](https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=operator-metrics-published-by-mq-container)).

## Referências

- [IBM MQ container image](https://github.com/ibm-messaging/mq-container)
- [Monitoring when using the IBM MQ Operator](https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=operator-monitoring-when-using-mq)
- [Metrics published by the IBM MQ container](https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=operator-metrics-published-by-mq-container)
