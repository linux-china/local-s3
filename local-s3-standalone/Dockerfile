FROM eclipse-temurin:21-jre-noble

LABEL org.opencontainers.image.authors="Fuxiang Luo <robothyluo@gmail.com>"

RUN groupadd -r locals3 && useradd -r -g locals3 locals3

RUN apt-get update && apt-get install --no-install-recommends --yes curl && rm -rf /var/lib/apt/lists/*

COPY --chown=locals3:locals3 build/libs/s3.jar /app/s3.jar

WORKDIR /app

VOLUME /data

EXPOSE 29090

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl --fail --silent --show-error http://localhost:29090/_health || exit 1

USER locals3

ENV LOCAL_S3_HOST="0.0.0.0"
ENV LOCAL_S3_MODE=PERSISTENCE

# Size the heap by the memory limit of the container. Override JAVA_OPTS to tune the JVM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

CMD exec java $JAVA_OPTS -jar s3.jar