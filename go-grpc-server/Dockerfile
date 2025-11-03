FROM golang:1.23.3

# Install protoc and required packages
RUN apt-get update && apt-get install -y \
    protobuf-compiler \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install gRPC health probe
RUN curl -L https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/v0.4.24/grpc_health_probe-linux-amd64 -o /usr/local/bin/grpc_health_probe && \
    chmod +x /usr/local/bin/grpc_health_probe

# Install Go plugins for protoc
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
RUN go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

WORKDIR /app

# Stage 1: Build dependencies (cached if go.mod/go.sum don't change)
# This layer is cached and dependencies are NOT re-downloaded if go.mod/go.sum unchanged
COPY go.mod go.sum ./
RUN go mod download

# Stage 2: Build binary (only rebuilds if source code changes)
# Docker layer caching means:
# - go.mod/go.sum layer is cached (dependencies not re-downloaded)
# - Source code layer only rebuilds if files change
COPY . .

ENV GOPROXY=https://proxy.golang.org,direct

RUN go install github.com/air-verse/air@v1.61.1
COPY config/air.toml /app/air.toml

# Make generate script executable if it's not already
RUN chmod +x scripts/generate_stubs.sh

# Generate protobuf code during build
RUN ./scripts/generate_stubs.sh

# Build binary - this step uses cached dependencies from go mod download
# Dependencies are already downloaded in earlier layer, so this is fast
RUN go build -o server ./cmd/grpc-server/main.go