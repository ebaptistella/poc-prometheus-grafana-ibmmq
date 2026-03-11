.PHONY: secrets up down logs status metrics metrics-mq-prometheus producer consumer build-app build-mq-prometheus run-producer run-consumer demo

# Cria os arquivos de secret para o IBM MQ 9.4 (obrigatório antes do primeiro docker compose up)
secrets:
	@mkdir -p secrets
	@printf '%s' 'passw0rd' > secrets/mqAdminPassword
	@printf '%s' 'passw0rd' > secrets/mqAppPassword
	@chmod 600 secrets/mqAdminPassword secrets/mqAppPassword
	@echo "Secrets criados em secrets/"

up:
	docker compose up -d

# Sobe tudo + producer e consumer em loop (produz/consome enquanto estiver no ar)
demo: secrets
	@docker compose build app --quiet 2>/dev/null || docker compose build app
	docker compose --profile demo up -d
	@echo ""
	@echo "Demo no ar: IBM MQ, Prometheus, Grafana, producer-loop (5 msgs a cada 20s), consumer-loop."
	@echo "Grafana: http://localhost:3000  |  Prometheus: http://localhost:9090  |  MQ Console: https://localhost:9443/ibmmq/console"
	@echo "Para parar: make down"

down:
	docker compose down

logs:
	docker compose logs -f

status:
	docker compose ps

# Testa o endpoint de métricas do MQ (requer curl)
metrics:
	@curl -s http://localhost:9157/metrics | head -80

# Testa o endpoint do mq_prometheus (filas/canais); útil para troubleshooting "No data"
metrics-mq-prometheus:
	@echo "=== mq_prometheus :9158/metrics (filas/canais) ==="
	@resp=$$(curl -s --connect-timeout 3 http://localhost:9158/metrics 2>/dev/null); \
	if [ -z "$$resp" ]; then \
	  echo "Erro: endpoint inacessível. Container mq_prometheus está rodando? (docker compose ps)"; \
	elif ! echo "$$resp" | grep -qE "^ibmmq_q_|^ibmmq_chl_"; then \
	  echo "Endpoint OK mas nenhuma métrica ibmmq_q_* ou ibmmq_chl_*."; \
	  echo "  Possível causa: collector ainda não conectou ao QM. Ver: docker compose logs mq_prometheus"; \
	  echo "$$resp" | head -20; \
	else \
	  echo "$$resp" | grep -E "^ibmmq_q_|^ibmmq_chl_" | head -40; \
	fi

# Producer: envia mensagens para DEV.QUEUE.1 (MQ no host local)
producer:
	cd app && mvn -q exec:java -Dexec.mainClass="com.poc.mq.Producer"

# Consumer: consome mensagens de DEV.QUEUE.1 (encerra após 5s sem mensagem)
consumer:
	cd app && mvn -q exec:java -Dexec.mainClass="com.poc.mq.Consumer"

# Build da imagem Docker do app (a partir de app/Dockerfile)
build-app:
	docker compose build app

# Build da imagem do mq_prometheus (mq-metric-samples; linux/amd64). Necessário na primeira vez ou ao atualizar o collector.
build-mq-prometheus:
	docker compose build mq_prometheus

# Roda o producer na rede Docker (conecta em ibmmq); requer: make up
run-producer:
	docker compose run --rm app com.poc.mq.Producer 5

# Roda o consumer na rede Docker (timeout 10s); requer: make up
run-consumer:
	docker compose run --rm app com.poc.mq.Consumer 10000
