FROM eclipse-temurin:25-jre
ARG KAFKA_VERSION=4.4.0
ARG KAFKA_SHA512=f5ceba8a265ab6bb2d68362b1f6859a95e15d60ef92ba83b43fdf065b557dc18e98d6383c15b9977320e25b3a2c8b6f11decb17b0fa6c0f163423793a0eb95cb
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/kafka_2.13-${KAFKA_VERSION}.tgz" -o /tmp/kafka.tgz \
 && echo "${KAFKA_SHA512}  /tmp/kafka.tgz" | sha512sum -c - \
 && mkdir /opt/kafka && tar -xzf /tmp/kafka.tgz --strip-components=1 -C /opt/kafka && rm /tmp/kafka.tgz
RUN groupadd --gid 1001 kafka && useradd --uid 1001 --gid kafka kafka && mkdir /var/lib/kafka && chown kafka:kafka /var/lib/kafka
COPY server.properties /opt/kafka/config/server.properties
COPY start.sh /opt/kafka/start.sh
RUN chmod +x /opt/kafka/start.sh
USER kafka
ENV KAFKA_HEAP_OPTS="-Xms256m -Xmx512m"
ENV LOG_DIR=/var/lib/kafka/logs
ENTRYPOINT ["/opt/kafka/start.sh"]
