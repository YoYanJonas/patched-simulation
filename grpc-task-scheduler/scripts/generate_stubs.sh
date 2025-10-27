#!/bin/bash

set -e

echo "Generating gRPC stubs for scheduler service..."

# Check if api/proto directory exists
if [ ! -d "api/proto" ]; then
    echo "Error: api/proto directory not found"
    exit 1
fi

# Check if scheduler.proto exists
if [ ! -f "api/proto/scheduler.proto" ]; then
    echo "Error: api/proto/scheduler.proto file not found"
    exit 1
fi

# Check if protoc is available
if ! command -v protoc &> /dev/null; then
    echo "Error: protoc is not installed"
    exit 1
fi

# Check if protoc-gen-go is available
if ! command -v protoc-gen-go &> /dev/null; then
    echo "Error: protoc-gen-go plugin is not installed"
    exit 1
fi

# Check if protoc-gen-go-grpc is available
if ! command -v protoc-gen-go-grpc &> /dev/null; then
    echo "Error: protoc-gen-go-grpc plugin is not installed"
    exit 1
fi

echo "All prerequisites found!"

# Remove previous generated files if they exist
echo "Cleaning up previous generated files..."
if [ -f "api/proto/scheduler.pb.go" ]; then
    rm -f "api/proto/scheduler.pb.go"
    echo "  - Removed api/proto/scheduler.pb.go"
fi

if [ -f "api/proto/scheduler_grpc.pb.go" ]; then
    rm -f "api/proto/scheduler_grpc.pb.go"
    echo "  - Removed api/proto/scheduler_grpc.pb.go"
fi

echo "Generating protobuf and gRPC files..."

# Generate Go stubs
protoc \
    --go_out=. \
    --go_opt=paths=source_relative \
    --go-grpc_out=. \
    --go-grpc_opt=paths=source_relative \
    api/proto/scheduler.proto

echo "Code generation completed!"

# Verify both generated files exist
echo "Verifying generated files..."
MISSING_FILES=0

if [ -f "api/proto/scheduler.pb.go" ]; then
    echo "api/proto/scheduler.pb.go - Generated successfully"
else
    echo "api/proto/scheduler.pb.go - NOT FOUND"
    MISSING_FILES=1
fi

if [ -f "api/proto/scheduler_grpc.pb.go" ]; then
    echo "api/proto/scheduler_grpc.pb.go - Generated successfully"
else
    echo "api/proto/scheduler_grpc.pb.go - NOT FOUND"
    MISSING_FILES=1
fi

if [ $MISSING_FILES -eq 0 ]; then
    echo ""
    echo "SUCCESS: All gRPC stub files generated and verified!"
    echo "Generated files:"
    echo "api/proto/scheduler.pb.go (protobuf messages)"
    echo "api/proto/scheduler_grpc.pb.go (gRPC service definitions)"
else
    echo ""
    echo "FAILURE: Some stub files were not generated properly"
    exit 1
fi