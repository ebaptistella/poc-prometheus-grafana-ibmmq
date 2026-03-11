# POC: IBM MQ + Prometheus + Grafana

Local setup that runs **IBM MQ** with metrics collection via **Prometheus** and visualization in **Grafana**.

## Architecture

```
┌─────────────┐     scrape      ┌────────────┐     query      ┌─────────┐
│   IBM MQ    │ ───────────────► │ Prometheus │ ◄───────────── │ Grafana │
│ :1414, :9157│   /metrics      │   :9090    │   datasource   │  :3000   │
└─────────────┘                 └────────────┘                └─────────┘
```

- **IBM MQ** (official image `icr.io/ibm-messaging/mq:9.4.x`): queue manager with a Prometheus metrics endpoint on port **9157** (`MQ_ENABLE_METRICS=true`).
- **mq_prometheus** ([ibm-messaging/mq-metric-samples](https://github.com/ibm-messaging/mq-metric-samples)): collector that connects to MQ as a client and exposes metrics **per queue** (depth, handles) and **per channel** on port **9158**. Built from the GitHub repo; config in `mq-prometheus/mq_prometheus.yaml`.
- **Prometheus**: scrapes metrics from MQ (9157) and mq_prometheus (9158); stores time series.
- **Grafana**: Prometheus datasource provisioned by default; login `admin` / `admin`.

## Prerequisites

- Docker and Docker Compose
- For IBM MQ 9.4+, you must accept the license (variable `LICENSE=accept` is set in the compose file) and provide passwords via **secrets**

## Quick start

### 1. Create secrets (required the first time)

IBM MQ 9.4 requires passwords via Docker secrets. Create the files:

```bash
make secrets
# or manually:
mkdir -p secrets
printf '%s' 'passw0rd' > secrets/mqAdminPassword
printf '%s' 'passw0rd' > secrets/mqAppPassword
```

### 2. Start the environment

The first time (or when you change the collector version), build the **mq_prometheus** image (built from the mq-metric-samples repo; may take a few minutes):

```bash
docker compose build mq_prometheus
```

Then start the stack:

```bash
docker compose up -d
```

Wait for the MQ healthcheck (~30s). The mq_prometheus collector uses the **admin** user (channel DEV.ADMIN.SVRCONN) to access system queues and publications; the password is in `mq-prometheus/mq_prometheus.yaml`. If you change `secrets/mqAdminPassword`, update the `password` field in that YAML. Then:

- **Grafana**: http://localhost:3000 (admin / admin)
- **Prometheus**: http://localhost:9090
- **IBM MQ Web Console**: https://localhost:9443/ibmmq/console (admin / passw0rd; accept the certificate)
- **MQ raw metrics**: http://localhost:9157/metrics

### 3. Check MQ metrics

```bash
make metrics
# or
curl -s http://localhost:9157/metrics | head -100
```

In Prometheus (http://localhost:9090), use **Explore** and filter by `ibmmq_` to see queue manager metrics.

### 4. IBM MQ dashboard in Grafana

A dashboard for IBM MQ metrics is **loaded automatically** via provisioning:

1. Open http://localhost:3000 and log in (admin / admin).
2. In the sidebar: **Dashboards** → **IBM MQ** folder → **IBM MQ – Queue Manager Metrics**.

The dashboard includes:

- **Overview**: CPU (1/5/15 min), free RAM, user/system CPU, QM RAM usage.
- **MQI operations**: Put/Get rate, commits/rollbacks, connections and MQOPEN/MQCLOSE.
- **Messages**: throughput in bytes, persistent/non-persistent messages, pub/sub.
- **Filesystem and log**: free disk space (QM, log, errors), log latency, usage in bytes.
- **Failures and errors**: failed MQPUT/MQGET/MQCONN/MQOPEN, expired messages, FDC.
- **Other MQI**: MQCTL, MQINQ, MQSET, MQCB, browse and purge.
- **Channels and connections**: active connections to the QM, connection/disconnection rate, MQOPEN/MQCLOSE; **mq_prometheus** exposes per-channel metrics (e.g. `ibmmq_channel_status`, `ibmmq_channel_status_squash`).
- **Queue lag / depth**: **mq_prometheus** panels — current and max depth per queue (`ibmmq_queue_depth`, `ibmmq_queue_attribute_max_depth`), capacity usage (%), read/write handles, number of monitored queues.

Default refresh: 30s.

### Monitoring disconnected channels

The MQ container’s native endpoint (port 9157) **does not** expose per-channel status (Running / Inactive / Stopped / Retrying). To get the same view as the MQ Console (count by status and list of channels with state):

1. **Use the mq_prometheus exporter** from [mq-metric-samples](https://github.com/ibm-messaging/mq-metric-samples): it connects to MQ as a client, collects per-channel metrics and exposes them over HTTP for Prometheus (e.g. port 9158).
2. **Configure Prometheus** to scrape that exporter (job pointing at the mq_prometheus port).
3. **In Grafana:**
   - **Disconnected channels panel:** use the per-channel metric (e.g. `ibmmq_channel_status` or `ibmmq_channel_status_squash` with labels like `channel`, `connname`, `qmgr`). Disconnected = status not RUNNING (e.g. Inactive, Stopped, Retrying). Example: count channels where status is not RUNNING.
   - **Alert:** create a Prometheus or Grafana alert when the count of “not running” channels is &gt; 0 (e.g. using the metric names exposed by your exporter).

That gives you “do I have a disconnected channel?” in the same way as the MQ Console (Inactive, Stopped, Retrying).

### Alerts (Prometheus)

Prometheus is configured with **alert rules** based on IBM MQ metrics. Rules are evaluated every 30s; when the condition holds for longer than the rule’s `for`, the alert goes to **Firing**.

**Where to see them:** Prometheus → **Status** → **Alerts** (http://localhost:9090/alerts). Firing alerts are shown in red.

**Included rules** (`prometheus/alerts/ibmmq.yml`): **IBMMQDown** (QM unreachable), **MQPrometheusDown** (mq_prometheus collector unreachable), **IBMMQHighCPU**, **IBMMQLowRAM**, **IBMMQLowDiskQM**, **IBMMQLowDiskLog**, **IBMMQFailedConnections**, **IBMMQFailedPut**, **IBMMQFailedGet**, **IBMMQLogWriteLatencyHigh**, **IBMMQQueueDepthHigh**, **IBMMQQueueDepthCritical**, **IBMMQQueueDepthNearMax**. For notifications (Slack, email), add Alertmanager. In Grafana: **Edit** on a panel → **Alert** → **Create alert rule** with the desired metric.

**Queue lag and alerts:** this POC uses **mq_prometheus** (mq-metric-samples), which exposes `ibmmq_queue_depth` and `ibmmq_queue_attribute_max_depth` per queue. Prometheus scrapes `mq_prometheus:9158`. Alerts **IBMMQQueueDepthHigh** (depth &gt; 500 for 5 min), **IBMMQQueueDepthCritical** (depth &gt; 5000 for 2 min) and **IBMMQQueueDepthNearMax** (depth &gt; 80% of max) fire when lag is high.

**Where to see lag alerts:**
1. **Grafana** — In the **IBM MQ – Queue Manager Metrics** dashboard, section **Lag / queue depth**, the **“Lag alerts (firing)”** panel shows which alerts are currently firing.
2. **Prometheus** — **Status → Alerts** (http://localhost:9090/alerts): full list (Pending and Firing) with queue, value and description.

Adjust thresholds in `prometheus/alerts/ibmmq.yml` (group `ibmmq_queue_lag`) as needed.

### Panels showing “No data” (queues/channels) — troubleshooting

**You’re not missing metrics.** The mq_prometheus (mq-metric-samples) exporter exposes **the same or more** than the old exporter: depth per queue (`ibmmq_queue_depth`), max capacity (`ibmmq_queue_attribute_max_depth`), read/write handles (`ibmmq_queue_input_handles`, `ibmmq_queue_output_handles`), oldest message age (`ibmmq_queue_oldest_message_age`), **plus** channel status (`ibmmq_channel_status`, `ibmmq_channel_status_squash`). “No data” usually means a **collection or configuration** issue, not missing metrics in the exporter.

To troubleshoot:

1. **Is the mq_prometheus collector UP?** At the top of the dashboard, the “mq_prometheus (per-queue metrics)” panel should show UP (green). If DOWN, the container is not responding on port 9158.
2. **Collector metrics on the host:**  
   `curl -s http://localhost:9158/metrics | grep "^ibmmq_" | head -30`  
   If you see lines like `ibmmq_queue_depth`, `ibmmq_channel_status_squash`, etc., the collector is exposing data; the issue may be Prometheus not scraping or Grafana using a different datasource/interval.
3. **Is Prometheus scraping?** At http://localhost:9090/targets check that the `mq_prometheus` target (job `mq_prometheus`) is **UP** and there are no scrape errors.
4. **mq_prometheus logs:**  
   `docker compose logs mq_prometheus`  
   Look for “Connected to queue manager”, connection errors (host/port, user/password) or queue discovery failures. The QM host must be the Docker service name: `ibmmq` (already set as `connName: ibmmq(1414)` in `mq-prometheus/mq_prometheus.yaml`).
5. **Image build:** The first run requires `docker compose build mq_prometheus` (build from GitHub; can take several minutes). If the build fails, queue/channel panels will have no data.
6. **MQRC_NOT_AUTHORIZED (2035) opening SYSTEM.ADMIN.COMMAND.QUEUE:** the collector needs a user with access to system queues (e.g. **admin**). In `mq-prometheus/mq_prometheus.yaml` use `channel: DEV.ADMIN.SVRCONN` and `user: admin` (password must match the mqAdminPassword secret).
7. **Endpoint OK but no ibmmq_queue_* / ibmmq_channel_* metrics:** not an exporter bug. mq_prometheus fills those metrics when (a) it receives QM publications on $SYS topics and (b) it can run DISPLAY QSTATUS/CHSTATUS (object status). Wait **1–2 minutes** after the container starts (the first batch of publications can be delayed). If still empty: set `logLevel: DEBUG` in `mq_prometheus.yaml`, restart the container and run `docker compose logs mq_prometheus` — look for subscription errors, “Connected to queue manager”, and queue/channel discovery messages.

## Useful commands

| Command | Description |
|--------|--------------|
| **`make demo`** | **Full demo:** creates secrets, builds the app, starts IBM MQ + Prometheus + Grafana + **loop producer** (5 msgs every 20s) + **loop consumer**. Produces and consumes while running. |
| `make up` | Starts only the stack (MQ, Prometheus, Grafana), no continuous producer/consumer |
| `make down` | Stops and removes all containers (including demo ones) |
| `make logs` | Follow logs |
| `make status` | List service status |
| `make metrics` | Shows a sample of the MQ /metrics endpoint (port 9157) |
| `make metrics-mq-prometheus` | Shows a sample of mq_prometheus metrics (port 9158; queues/channels) — useful when queue/channel panels show “No data” |

## Producer and consumer (queue DEV.QUEUE.1)

A Java (JMS) application sends and consumes messages on queue **DEV.QUEUE.1**:

- **Producer**: sends text messages to the queue.
- **Consumer**: consumes messages from the queue (exits after a few seconds with no new message).

With MQ running (`docker compose up -d`), run the consumer in one terminal and the producer in another:

```bash
cd app
# Terminal 1 – consumer (waits for messages)
mvn exec:java -Dexec.mainClass="com.poc.mq.Consumer"

# Terminal 2 – producer (sends 5 messages)
mvn exec:java -Dexec.mainClass="com.poc.mq.Producer"
```

Configuration via environment variables: `MQ_HOST`, `MQ_PORT`, `MQ_QUEUE`, `MQ_USER`, `MQ_PASSWORD`, etc. (defaults: localhost:1414, DEV.QUEUE.1, app/passw0rd). See `app/README.md`.

### Why does queue depth (lag) grow in the demo?

In the **demo** (`make demo`), the **producer-loop** sends **100 messages** per batch and waits **3–15 s** between batches; the **consumer-loop** consumes **one message at a time** and, after 25 s with no message, exits and waits 2 s before reconnecting. So:

- **Producer:** ~100 msgs every ~9 s on average → spikes of 100 messages.
- **Consumer:** one consumer, one message per `receive()`; 2 s pause between cycles.

So the producer tends to put messages on the queue faster than a single consumer can drain it. **Depth at 5k (or more) is expected** if the demo runs for a while — producer and consumer are working; the imbalance is in throughput.

**To reduce lag:**

1. **Drain only (no produce):** stop the producer (`docker compose stop producer-loop`) and let only the consumer run until the queue drains.
2. **Balance the demo:** reduce batch size or increase producer interval (e.g. `Producer random 30 10 20` = 30 msgs every 10–20 s) or run more consumers (multiple `consumer-loop` or manual instances).
3. **Clear the queue:** in the MQ Console (https://localhost:9443/ibmmq/console), on queue DEV.QUEUE.1 use **Clear** (purge) to reset the backlog.

## Project structure

```
.
├── app/                     # Java (JMS) producer and consumer
│   ├── pom.xml
│   ├── README.md
│   └── src/main/java/com/poc/mq/
│       ├── MqConfig.java
│       ├── Producer.java
│       └── Consumer.java
├── docker-compose.yml
├── mq-prometheus/            # mq_prometheus (ibm-messaging/mq-metric-samples): mq_prometheus.yaml (queues, QM, channel, port 9158)
├── prometheus/
│   ├── prometheus.yml       # scrape config and rule_files
│   └── alerts/
│       └── ibmmq.yml        # alert rules (CPU, disk, MQI failures, etc.)
├── grafana/
│   └── provisioning/
│       ├── datasources/
│       │   └── datasources.yml
│       └── dashboards/
│           ├── dashboards.yml    # provider that loads the default/ folder
│           └── default/
│               └── ibmmq-metrics.json   # IBM MQ dashboard
├── secrets/                 # mqAdminPassword, mqAppPassword (not versioned)
├── Makefile
└── README.md
```

## IBM MQ metrics (examples)

With `MQ_ENABLE_METRICS=true`, the container exposes Prometheus-format metrics, for example:

- `ibmmq_qmgr_*` – queue manager (commits, CPU, MQI calls, etc.)
- Other system metrics published by MQ (see [IBM documentation](https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=operator-metrics-published-by-mq-container)).

## References

- [IBM MQ container image](https://github.com/ibm-messaging/mq-container)
- [Monitoring when using the IBM MQ Operator](https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=operator-monitoring-when-using-mq)
- [Metrics published by the IBM MQ container](https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=operator-metrics-published-by-mq-container)
