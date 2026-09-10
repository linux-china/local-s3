FROM frolvlad/alpine-glibc:alpine-3.22_glibc-2.42

MAINTAINER Fuxiang Luo <robothyluo@gmail.com>

WORKDIR /app

COPY build/bin/s3 /app/s3

EXPOSE 80

CMD exec ./s3