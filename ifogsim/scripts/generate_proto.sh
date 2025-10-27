#!/bin/bash

# Generate proto files for iFogSim
echo "Generating proto files..."

# Run Maven to generate proto files
mvn clean compile

# Check if Maven build was successful
if [ $? -eq 0 ]; then
    echo "Proto files generated successfully!"
    echo "Generated files are in: target/generated-sources/protobuf/java"
    
    # Verify all required files exist
    echo "Verifying generated files..."
    
    # Check Java proto files
    if [ -f "target/generated-sources/protobuf/java/org/patch/proto/IfogsimCommon.java" ] && \
       [ -f "target/generated-sources/protobuf/java/org/patch/proto/IfogsimScheduler.java" ] && \
       [ -f "target/generated-sources/protobuf/java/org/patch/proto/IfogsimAllocation.java" ]; then
        echo "✅ All Java proto files generated successfully"
        ls -la target/generated-sources/protobuf/java/org/patch/proto/
    else
        echo "❌ Some Java proto files are missing"
        exit 1
    fi
    
    # Check gRPC stub files
    if [ -f "target/generated-sources/protobuf/grpc-java/org/patch/proto/TaskSchedulerGrpc.java" ] && \
       [ -f "target/generated-sources/protobuf/grpc-java/org/patch/proto/FogAllocationServiceGrpc.java" ] && \
       [ -f "target/generated-sources/protobuf/grpc-java/org/patch/proto/SystemMonitoringGrpc.java" ]; then
        echo "✅ All gRPC stub files generated successfully"
        ls -la target/generated-sources/protobuf/grpc-java/org/patch/proto/
    else
        echo "❌ Some gRPC stub files are missing"
        exit 1
    fi
    
    echo "🎉 All proto files generated and verified successfully!"
else
    echo "❌ Proto generation failed!"
    echo "Please check the Maven output above for errors."
    exit 1
fi