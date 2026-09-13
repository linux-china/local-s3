FROM frolvlad/alpine-glibc:alpine-3.22_glibc-2.42

LABEL org.opencontainers.image.authors="Fuxiang Luo <robothyluo@gmail.com>"

WORKDIR /app

VOLUME /data

COPY build/bin/s3 /app/s3

EXPOSE 29090

CMD exec ./s3