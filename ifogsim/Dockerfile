FROM openjdk:8-jdk-alpine

# Install Maven
RUN apk add --no-cache maven

# Install gRPC health probe for health checks
RUN apk add --no-cache curl
RUN curl -L https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/v0.4.24/grpc_health_probe-linux-amd64 -o /usr/local/bin/grpc_health_probe
RUN chmod +x /usr/local/bin/grpc_health_probe

# Set working directory
WORKDIR /app

# Copy Maven files first for better caching
COPY pom.xml .
COPY go.mod go.sum ./

# Copy source code
COPY . .

# Create necessary directories
RUN mkdir -p /app/results /app/logs /app/config /app/target

# Generate proto files
RUN chmod +x scripts/generate_proto.sh
RUN ./scripts/generate_proto.sh

# Build the project
RUN mvn clean compile package -DskipTests

# Create a simple health check script
RUN echo '#!/bin/sh\njava -cp "target/classes:target/dependency/*" org.fog.test.SimpleExample --health-check || exit 1' > /usr/local/bin/health-check.sh
RUN chmod +x /usr/local/bin/health-check.sh

# Set environment variables
ENV JAVA_OPTS="-Xmx2g -Xms1g -Djava.awt.headless=true"
ENV MAVEN_OPTS="-Xmx1g"

# Expose port for potential web interface
EXPOSE 8080

# Default command
CMD ["java", "-cp", "target/classes:target/dependency/*", "org.fog.test.SimpleExample"]
