# Quartz Notes sync server: PocketBase with the Quartz schema, sync rules and (later) the web app.
FROM alpine:3.20

ARG PB_VERSION=0.40.5
ARG TARGETARCH=amd64

RUN apk add --no-cache ca-certificates tzdata unzip wget \
    && wget -q "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/pocketbase_${PB_VERSION}_linux_${TARGETARCH}.zip" -O /tmp/pb.zip \
    && unzip -q /tmp/pb.zip pocketbase -d /pb \
    && rm /tmp/pb.zip

COPY pb_migrations /pb/pb_migrations
COPY pb_hooks /pb/pb_hooks
COPY pb_public /pb/pb_public

EXPOSE 8090
VOLUME /pb/pb_data

HEALTHCHECK --interval=30s --timeout=5s CMD wget -q -O /dev/null http://127.0.0.1:8090/api/health || exit 1

CMD ["/pb/pocketbase", "serve", "--http=0.0.0.0:8090", "--dir=/pb/pb_data", \
     "--migrationsDir=/pb/pb_migrations", "--hooksDir=/pb/pb_hooks", "--publicDir=/pb/pb_public", "--hooksWatch=false"]
