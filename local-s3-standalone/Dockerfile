FROM eclipse-temurin:25-jre-noble

LABEL org.opencontainers.image.authors="Fuxiang Luo <robothyluo@gmail.com>"

RUN groupadd -r locals3 && useradd -r -g locals3 locals3

RUN apt-get update && apt-get install --no-install-recommends --yes curl && rm -rf /var/lib/apt/lists/*

COPY --chown=locals3:locals3 build/libs/s3.jar /app/s3.jar

WORKDIR /app

# AOT cache: load and link the classes that the service loads to start and to answer the common requests, and profile
# their methods, ahead of time, which more than halves its startup time. The cache is only valid for this JDK, this jar
# at this path, and compact object headers, which the CMD enables before JAVA_OPTS for that reason; a JVM that can't use
# it, e.g. with another jar mounted over /app/s3.jar, or with -XX:-UseCompactObjectHeaders or ZGC in JAVA_OPTS, warns
# and starts without it. Compact object headers take 4 bytes less per object, which the many small objects of the
# metadata make up for.
RUN --mount=type=bind,source=aot-training.sh,target=/tmp/aot-training.sh \
    bash /tmp/aot-training.sh /app/s3.jar /app/app.aot -XX:+UseCompactObjectHeaders -XX:+UseSerialGC

# The data directory of a PERSISTENCE service, which this image runs by default. It has to belong to the user
# the service runs as, and before VOLUME: an anonymous volume, i.e. the one that `docker run` without a bind
# mount creates, is initialized from this directory of the image, its ownership included, so a /data left to
# root leaves the service unable to write its store. A bind-mounted directory keeps the ownership it has on
# the host instead, and must be writable by this user.
RUN mkdir -p /data && chown locals3:locals3 /data

VOLUME /data

EXPOSE 29090

# HTTPS first, for a service that serves it, possibly alone (LOCAL_S3_TLS_REQUIRED=true), with a certificate that the
# container doesn't trust, e.g. a self-signed one; then plain HTTP, for a service without TLS.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl --fail --silent --insecure "https://localhost:${LOCAL_S3_PORT:-29090}/_health" \
        || curl --fail --silent --show-error "http://localhost:${LOCAL_S3_PORT:-29090}/_health" || exit 1

USER locals3

ENV LOCAL_S3_HOST="0.0.0.0"
ENV LOCAL_S3_MODE=PERSISTENCE
# A container doesn't inherit the shell of a developer, so AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY can't be the
# client credentials of one there: keep reading them as the credentials of the service, as the image always did.
ENV LOCAL_S3_CREDENTIALS_FROM_AWS_ENV=true

# Size the heap by the memory limit of the container, and collect it with the Serial GC, which takes the least memory
# and CPU for the heaps of a few GB that a test or development service has. Override JAVA_OPTS to tune the JVM, e.g.
# with -XX:+UseG1GC for a large heap. The AOT cache is used whatever JAVA_OPTS says, unless it disables compact object
# headers or selects ZGC.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

CMD exec java -XX:AOTCache=/app/app.aot -XX:+UseCompactObjectHeaders $JAVA_OPTS -jar s3.jar