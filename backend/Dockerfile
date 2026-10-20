# Build context: the backend/ directory.
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/sparrowd ./cmd/sparrowd && mkdir -p /out/data

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/sparrowd /usr/local/bin/sparrowd
# Owned by the nonroot user, so a fresh named volume is writable.
COPY --from=build --chown=65532:65532 /out/data /var/lib/sparrow
ENV SPARROW_DATA_DIR=/var/lib/sparrow
VOLUME /var/lib/sparrow
EXPOSE 8443
# Distroless has no shell or curl: the binary probes its own /healthz.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD ["/usr/local/bin/sparrowd", "healthz"]
ENTRYPOINT ["/usr/local/bin/sparrowd"]
