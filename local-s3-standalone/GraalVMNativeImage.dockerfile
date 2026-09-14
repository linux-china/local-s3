FROM frolvlad/alpine-glibc:alpine-3.22_glibc-2.42

LABEL org.opencontainers.image.authors="Fuxiang Luo <robothyluo@gmail.com>"

RUN apk add --no-cache curl

WORKDIR /app

VOLUME /data

COPY build/bin/s3 /app/s3

EXPOSE 29090

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl --fail --silent --show-error http://localhost:29090/_health || exit 1

ENV LOCAL_S3_HOST="0.0.0.0"
ENV LOCAL_S3_MODE=PERSISTENCE

CMD exec ./s3