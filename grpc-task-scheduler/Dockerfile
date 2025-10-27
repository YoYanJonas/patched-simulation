FROM golang:1.23.3

# Install protoc and required packages
RUN apt-get update && apt-get install -y \
    protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*


ENV GOPROXY=https://proxy.golang.org,direct

# Install Go plugins for protoc
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
RUN go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

WORKDIR /app

COPY go.mod go.sum ./

RUN go mod tidy

COPY . .


RUN go install github.com/air-verse/air@v1.61.1

COPY config/air.toml /app/air.toml

# Make generate script executable if it's not already
RUN chmod +x scripts/generate_stubs.sh

# Generate protobuf code during build
RUN ./scripts/generate_stubs.sh

RUN go build -o server ./cmd/server/main.go