#!/bin/sh
# scripts/generate_stubs.sh
set -e

echo "Cleaning previous generated code..."
rm -f api/proto/*.pb.go

echo "Generating protobuf code..."
protoc --go_out=. --go_opt=paths=source_relative \
 --go-grpc_out=. --go-grpc_opt=paths=source_relative \
 api/proto/service.proto

echo "Proto generation completed successfully!"

# Print result
echo "Generated files:"
find api/proto -name "*.pb.go" | sort