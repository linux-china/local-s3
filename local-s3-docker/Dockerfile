FROM openjdk:21-ea-slim

COPY build/libs/s3.jar /app/s3.jar

WORKDIR /app

VOLUME /data

EXPOSE 29090

CMD exec java -jar s3.jar