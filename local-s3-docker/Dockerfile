FROM eclipse-temurin:21-jre-noble

LABEL org.opencontainers.image.authors="Fuxiang Luo <robothyluo@gmail.com>"

COPY build/libs/s3.jar /app/s3.jar

WORKDIR /app

VOLUME /data

EXPOSE 29090

# Size the heap by the memory limit of the container. Override JAVA_OPTS to tune the JVM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

CMD exec java $JAVA_OPTS -jar s3.jar