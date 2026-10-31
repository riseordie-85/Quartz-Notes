# Quartz Notes sync server: PocketBase with the Quartz schema, the sync rules and the web app.
# Built from the repository root: docker build -f server/Dockerfile .

FROM node:22-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
# Notebook icons and starter templates are shared with the app.
COPY shared/icons /src/shared/icons
COPY shared/templates /src/shared/templates
RUN npm run build

FROM alpine:3.20

ARG PB_VERSION=0.40.5
ARG TARGETARCH=amd64

RUN apk add --no-cache ca-certificates tzdata unzip wget \
    && wget -q "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/pocketbase_${PB_VERSION}_linux_${TARGETARCH}.zip" -O /tmp/pb.zip \
    && unzip -q /tmp/pb.zip pocketbase -d /pb \
    && rm /tmp/pb.zip

COPY server/pb_migrations /pb/pb_migrations
COPY server/pb_hooks /pb/pb_hooks
COPY --from=web /src/web/dist /pb/pb_public

EXPOSE 8090
VOLUME /pb/pb_data

HEALTHCHECK --interval=30s --timeout=5s CMD wget -q -O /dev/null http://127.0.0.1:8090/api/health || exit 1

CMD ["/pb/pocketbase", "serve", "--http=0.0.0.0:8090", "--dir=/pb/pb_data", \
     "--migrationsDir=/pb/pb_migrations", "--hooksDir=/pb/pb_hooks", "--publicDir=/pb/pb_public", "--hooksWatch=false"]
