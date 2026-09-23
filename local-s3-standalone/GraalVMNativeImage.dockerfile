FROM frolvlad/alpine-glibc:alpine-3.22_glibc-2.42

LABEL org.opencontainers.image.authors="Fuxiang Luo <robothyluo@gmail.com>"

RUN addgroup -S locals3 && adduser -S -G locals3 locals3

RUN apk add --no-cache curl

WORKDIR /app

COPY --chown=locals3:locals3 build/bin/s3 /app/s3

# The data directory of a PERSISTENCE service, which this image runs by default. It has to belong to the user
# the service runs as, and before VOLUME: an anonymous volume, i.e. the one that `docker run` without a bind
# mount creates, is initialized from this directory of the image, its ownership included, so a /data left to
# root leaves the service unable to write its store. A bind-mounted directory keeps the ownership it has on
# the host instead, and must be writable by this user.
RUN mkdir -p /data && chown locals3:locals3 /data

VOLUME /data

EXPOSE 29090

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl --fail --silent --show-error http://localhost:29090/_health || exit 1

USER locals3

ENV LOCAL_S3_HOST="0.0.0.0"
ENV LOCAL_S3_MODE=PERSISTENCE

CMD exec ./s3
