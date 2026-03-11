# Multi-stage: build com Maven, runtime com JRE
# Imagens Debian (não Alpine) para suportar arm64/Apple Silicon
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Cache de dependências
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build do projeto
COPY src ./src
RUN mvn package -B -DskipTests -Dmaven.javadoc.skip=true \
    && mv target/mq-producer-consumer-*-jar-with-dependencies.jar target/app.jar

# Imagem final (Debian: multi-arch, incluindo arm64)
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=builder /build/target/app.jar /app/app.jar

# Main class padrão: Producer. Para Consumer: override com o 1º argumento.
# Ex.: docker run ... com.poc.mq.Consumer
#      docker run ... com.poc.mq.Producer 10
ENTRYPOINT ["java", "-cp", "/app/app.jar"]
CMD ["com.poc.mq.Producer"]
