#!/bin/bash
# start-run.sh: Start Docker Compose with health checks and wait for simulation
# Usage: start-run.sh [SCENARIO_NAME] [RUN_ID]
#   SCENARIO_NAME: Name of scenario (default: rl-full-feature)
#   RUN_ID: Run identifier (default: from prepare-run.sh or auto-generated)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load variables from prepare-run.sh if they exist, or use defaults
SCENARIO_NAME="${1:-${SCENARIO_NAME:-rl-full-feature}}"
RUN_ID="${2:-${RUN_ID:-$(date -u +%Y-%m-%dT%H-%M-%S)}}"

REPORT_DIR="$PROJECT_ROOT/reports/${RUN_ID}_${SCENARIO_NAME}"

echo "================================================================================"
echo "Starting simulation run: ${RUN_ID}_${SCENARIO_NAME}"
echo "================================================================================"

# Validate report directory exists
if [ ! -d "$REPORT_DIR" ]; then
    echo "Error: Report directory not found. Run prepare-run.sh first."
    exit 1
fi

cd "$PROJECT_ROOT"

# Export environment variables for docker-compose
export SCENARIO_NAME
export RUN_ID

# Start servers (allocator and schedulers) first
echo "Starting servers (allocator, scheduler-1, scheduler-2, scheduler-3)..."
docker compose up -d allocator scheduler-1 scheduler-2 scheduler-3

# Wait for all servers to be healthy
echo "Waiting for servers to be healthy..."
MAX_WAIT=120  # Maximum wait time in seconds
ELAPSED=0

check_health() {
    local service=$1
    docker compose ps "$service" | grep -q "healthy"
}

wait_for_services() {
    local services=("allocator" "scheduler-1" "scheduler-2" "scheduler-3")
    
    while [ $ELAPSED -lt $MAX_WAIT ]; do
        local all_healthy=true
        for service in "${services[@]}"; do
            if ! check_health "$service"; then
                all_healthy=false
                break
            fi
        done
        
        if [ "$all_healthy" = true ]; then
            echo "All servers are healthy!"
            return 0
        fi
        
        echo "Waiting for services to be healthy... ($ELAPSED/$MAX_WAIT seconds)"
        sleep 5
        ELAPSED=$((ELAPSED + 5))
    done
    
    echo "Warning: Timeout waiting for services to be healthy"
    return 1
}

wait_for_services || echo "Proceeding anyway (some services may not be healthy)"

# Start iFogSim simulation locally (not in Docker)
echo "Starting iFogSim simulation locally..."
echo "--- Simulation output ---"

# Set environment variables for local execution
export CONFIG_DIR="$PROJECT_ROOT/config/${SCENARIO_NAME}/simulation"
export REPORT_DIR="$REPORT_DIR/simulation"
export ALLOCATION_HOST=localhost
export ALLOCATION_PORT=50051
export SCHEDULER_1_HOST=localhost
export SCHEDULER_1_PORT=50052
export SCHEDULER_2_HOST=localhost
export SCHEDULER_2_PORT=50053
export SCHEDULER_3_HOST=localhost
export SCHEDULER_3_PORT=50054

# Additional environment variables for configuration
export CLOUD_RL_SERVER_HOST=localhost
export CLOUD_RL_SERVER_PORT=50051
export EXTERNAL_TASK_SERVER_HOST=localhost
export EXTERNAL_TASK_SERVER_PORT=50051
export PLACEMENT_RL_SERVER_HOST=localhost
export PLACEMENT_RL_SERVER_PORT=50051

# Log file in project root
LOG_FILE="$PROJECT_ROOT/x.log"
echo "Logs will be captured to: $LOG_FILE"

# Run ifogsim locally using Maven with log capture
cd "$PROJECT_ROOT/ifogsim"

# Run simulation and capture both stdout and stderr to x.log using tee
# This preserves console output while also saving to file
mvn exec:java -Dexec.mainClass="scenarios.RL3FogSimulation" \
    -Dexec.args="" \
    -Dexec.classpathScope=compile \
    2>&1 | tee "$LOG_FILE"

# Check exit code (Maven exit code, not tee)
EXIT_CODE=${PIPESTATUS[0]}
if [ $EXIT_CODE -eq 0 ]; then
    echo "================================================================================"
    echo "Simulation completed successfully"
    echo "================================================================================"
    echo "Logs saved to: $LOG_FILE"
else
    echo "================================================================================"
    echo "Simulation exited with error code: $EXIT_CODE"
    echo "================================================================================"
    echo "Check logs in: $LOG_FILE"
    exit $EXIT_CODE
fi

