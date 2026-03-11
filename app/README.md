# Producer e Consumer – IBM MQ (DEV.QUEUE.1)

Aplicação Java (JMS) que envia e consome mensagens na fila **DEV.QUEUE.1** do IBM MQ.

## Pré-requisitos

- Java 17+
- Maven 3.6+
- IBM MQ no ar (ex.: `docker compose up -d` na raiz do projeto)

## Configuração

Variáveis de ambiente (opcionais; valores abaixo são os defaults):

| Variável           | Default        | Descrição              |
|--------------------|----------------|------------------------|
| `MQ_QMGR`          | QM1            | Nome do queue manager  |
| `MQ_HOST`          | localhost      | Host do MQ              |
| `MQ_PORT`          | 1414           | Porta do listener       |
| `MQ_CHANNEL`       | DEV.APP.SVRCONN| Canal                   |
| `MQ_QUEUE`         | DEV.QUEUE.1    | Nome da fila            |
| `MQ_USER`          | app            | Usuário                 |
| `MQ_PASSWORD`      | passw0rd       | Senha                   |
| `CONSUMER_TIMEOUT_MS` | 5000        | Timeout (ms) sem mensagem para encerrar o consumer |

Para rodar **dentro da rede Docker** (com `ibmmq` como host):

```bash
export MQ_HOST=ibmmq
```

## Uso

### Producer (envia mensagens)

```bash
cd app
mvn exec:java -Dexec.mainClass="com.poc.mq.Producer"
```

Envia 5 mensagens por padrão. Para enviar N mensagens:

```bash
mvn exec:java -Dexec.mainClass="com.poc.mq.Producer" -Dexec.args="10"
```

### Consumer (recebe mensagens)

```bash
cd app
mvn exec:java -Dexec.mainClass="com.poc.mq.Consumer"
```

Fica aguardando mensagens; encerra após 5 segundos sem receber nada (ou use `CONSUMER_TIMEOUT_MS` / primeiro argumento em ms).

Exemplo com timeout de 10 segundos:

```bash
mvn exec:java -Dexec.mainClass="com.poc.mq.Consumer" -Dexec.args="10000"
```

## Ordem sugerida

1. Subir o MQ: `docker compose up -d` (na raiz).
2. Rodar o **consumer** em um terminal (fica esperando).
3. Rodar o **producer** em outro terminal; as mensagens devem aparecer no consumer.

## Build e execução com Docker

Na raiz do repositório (onde está o `docker-compose.yml`):

```bash
# Build da imagem do app
make build-app
# ou: docker compose build app

# Com MQ já no ar (make up), rodar producer (envia 5 mensagens)
make run-producer
# ou: docker compose run --rm app com.poc.mq.Producer 5

# Rodar consumer (espera mensagens; encerra após 10s sem nova)
make run-consumer
# ou: docker compose run --rm app com.poc.mq.Consumer 10000
```

A imagem usa a rede do Compose e as variáveis `MQ_HOST=ibmmq`, etc., para conectar ao container do MQ.

## Estrutura

```
app/
├── Dockerfile
├── .dockerignore
├── pom.xml
├── README.md
└── src/main/java/com/poc/mq/
    ├── MqConfig.java   # ConnectionFactory e defaults
    ├── Producer.java   # Put em DEV.QUEUE.1
    └── Consumer.java   # Get de DEV.QUEUE.1
```
