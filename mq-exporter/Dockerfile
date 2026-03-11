# Build mq_exporter com IBM MQ C client (redistributable). Só linux/amd64 (cliente IBM).
# mq_exporter exige go >= 1.24 (go.mod)
FROM golang:1.24-bookworm AS builder
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# genmqpkg.sh remove partes do pacote; estes flags mantêm SDK (headers) e libs para compilar
ENV genmqpkg_incnls=1 \
    genmqpkg_incsdk=1 \
    genmqpkg_inctls=1

ARG MQ_VER=9.4.5.0
RUN mkdir -p /opt/mqm && cd /opt/mqm \
    && curl -sLO "https://public.dhe.ibm.com/ibmdl/export/pub/software/websphere/messaging/mqdev/redist/${MQ_VER}-IBM-MQC-Redist-LinuxX64.tar.gz" \
    && tar -zxf ./*.tar.gz && rm -f ./*.tar.gz \
    && [ -x bin/genmqpkg.sh ] && ./bin/genmqpkg.sh -b /opt/mqm

# Headers e libs do MQ para cgo (incl. dependência mq-golang)
ENV CGO_ENABLED=1 \
    CGO_CFLAGS="-I/opt/mqm/inc" \
    CGO_LDFLAGS="-L/opt/mqm/lib64 -Wl,-rpath,/opt/mqm/lib64" \
    C_INCLUDE_PATH="/opt/mqm/inc" \
    CPATH="/opt/mqm/inc" \
    LIBRARY_PATH="/opt/mqm/lib64"

WORKDIR /go
RUN git clone --depth 1 https://github.com/agebhar1/mq_exporter.git \
    && cd mq_exporter && go build -o mq_exporter .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/mqm /etc/mq_exporter
COPY --from=builder /opt/mqm /opt/mqm
COPY --from=builder /go/mq_exporter/mq_exporter /usr/local/bin/mq_exporter
ENV LD_LIBRARY_PATH=/opt/mqm/lib64
EXPOSE 9873
ENTRYPOINT ["mq_exporter"]
CMD ["--config=/etc/mq_exporter/config.yml", "--web.listen-address=:9873"]
