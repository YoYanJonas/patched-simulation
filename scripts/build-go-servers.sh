#!/bin/bash
# build-go-servers.sh: Build Go server binaries locally
# This builds the binaries before Docker, allowing Docker to use pre-built binaries
# and avoid re-downloading dependencies

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "================================================================================"
echo "Building Go server binaries locally"
echo "================================================================================"

# Create build directories
mkdir -p "$PROJECT_ROOT/build/go-grpc-server"
mkdir -p "$PROJECT_ROOT/build/grpc-task-scheduler"

# Build allocator (go-grpc-server)
echo ""
echo "Building allocator (go-grpc-server)..."
cd "$PROJECT_ROOT/go-grpc-server"

# Generate protobuf stubs if needed
if [ -f "scripts/generate_stubs.sh" ]; then
    echo "  Generating protobuf stubs..."
    ./scripts/generate_stubs.sh
fi

# Build the binary
echo "  Building binary..."
go build -o "$PROJECT_ROOT/build/go-grpc-server/server" ./cmd/grpc-server/main.go

if [ -f "$PROJECT_ROOT/build/go-grpc-server/server" ]; then
    echo "  ✓ Allocator binary built: build/go-grpc-server/server"
else
    echo "  ✗ Failed to build allocator binary"
    exit 1
fi

# Build scheduler (grpc-task-scheduler)
echo ""
echo "Building scheduler (grpc-task-scheduler)..."
cd "$PROJECT_ROOT/grpc-task-scheduler"

# Generate protobuf stubs if needed
if [ -f "scripts/generate_stubs.sh" ]; then
    echo "  Generating protobuf stubs..."
    ./scripts/generate_stubs.sh
fi

# Build the binary
echo "  Building binary..."
go build -o "$PROJECT_ROOT/build/grpc-task-scheduler/server" ./cmd/server/main.go

if [ -f "$PROJECT_ROOT/build/grpc-task-scheduler/server" ]; then
    echo "  ✓ Scheduler binary built: build/grpc-task-scheduler/server"
else
    echo "  ✗ Failed to build scheduler binary"
    exit 1
fi

echo ""
echo "================================================================================"
echo "All Go server binaries built successfully"
echo "================================================================================"
echo ""
echo "Binaries location:"
echo "  - Allocator:  build/go-grpc-server/server"
echo "  - Scheduler:  build/grpc-task-scheduler/server"
echo ""
echo "Docker will use these pre-built binaries instead of building inside containers"

