FROM golang:1.23.3 AS builder

# Install protoc and required packages
RUN apt-get update && apt-get install -y \
    protobuf-compiler \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install gRPC health probe
RUN curl -L https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/v0.4.24/grpc_health_probe-linux-amd64 -o /usr/local/bin/grpc_health_probe && \
    chmod +x /usr/local/bin/grpc_health_probe

# Set Go proxy for faster downloads
ENV GOPROXY=https://proxy.golang.org,direct

# Install Go plugins for protoc (cached layer - rarely changes)
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest && \
    go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

WORKDIR /app

# Stage 1: Copy dependency files and download dependencies
# This layer is CACHED if go.mod/go.sum don't change
# Dependencies are NOT re-downloaded when only source code changes
COPY go.mod go.sum ./
RUN go mod download && \
    go mod verify

# Stage 2: Copy proto files and generate stubs
# This layer is CACHED if proto files don't change
# Proto generation only runs when proto files change
COPY api/proto/*.proto api/proto/
COPY scripts/ scripts/
RUN chmod +x scripts/generate_stubs.sh && \
    ./scripts/generate_stubs.sh

# Stage 3: Copy source code and build
# This layer rebuilds when source code changes
# BUT dependencies are already cached from Stage 1
COPY . .

# Build binary - uses cached dependencies from Stage 1
# Only rebuilds when source code changes
RUN go build -o server ./cmd/server/main.go

# Final stage: minimal runtime image
FROM golang:1.23.3

# Copy only the binary and health probe
COPY --from=builder /app/server /app/server
COPY --from=builder /usr/local/bin/grpc_health_probe /usr/local/bin/grpc_health_probe

WORKDIR /app

# Run the server
CMD ["./server"]