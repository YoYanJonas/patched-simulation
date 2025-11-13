#!/bin/bash
# finish-run.sh: Stop all containers and verify reports
# Usage: finish-run.sh [SCENARIO_NAME] [RUN_ID]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SCENARIO_NAME="${1:-${SCENARIO_NAME:-rl-full-feature}}"
RUN_ID="${2:-${RUN_ID:-$(date -u +%Y-%m-%dT%H-%M-%S)}}"

REPORT_DIR="$PROJECT_ROOT/reports/${RUN_ID}_${SCENARIO_NAME}"

echo "================================================================================"
echo "Finishing run: ${RUN_ID}_${SCENARIO_NAME}"
echo "================================================================================"

cd "$PROJECT_ROOT"

# Collect server logs BEFORE stopping containers
echo ""
echo "Collecting server logs from containers..."
echo "================================================================================"

mkdir -p "$REPORT_DIR/allocator/logs"
mkdir -p "$REPORT_DIR/scheduler/node1/logs"
mkdir -p "$REPORT_DIR/scheduler/node2/logs"
mkdir -p "$REPORT_DIR/scheduler/node3/logs"

collect_logs() {
    local service=$1
    local log_dir=$2
    local log_file="$log_dir/server.log"
    
    echo "Collecting logs from $service..."
    
    # Server writes logs directly to report directory via REPORT_PATH env var
    # Logs are written to $REPORT_PATH/logs/server.log inside container
    # Which is mounted to $REPORT_DIR/scheduler/nodeX/logs/server.log on host
    # So we just need to check if the log file exists and copy it
    
    if [ -f "$log_file" ] && [ -s "$log_file" ]; then
        # Log file already exists (written by server directly)
        local line_count=$(wc -l < "$log_file" 2>/dev/null || echo "0")
        echo "  ✓ Found log file: $log_file ($line_count lines)"
        
        # Ensure proper permissions
        chmod 644 "$log_file" 2>/dev/null || true
        chown "$USER:$USER" "$log_file" 2>/dev/null || true
        
        local error_count=$(grep -iE "error|fatal|panic|failed|exception" "$log_file" 2>/dev/null | wc -l || echo "0")
        if [ "$error_count" -gt 0 ]; then
            echo "  ⚠ Warning: Found $error_count potential errors in logs"
            echo "    Sample errors:"
            grep -iE "error|fatal|panic|failed|exception" "$log_file" 2>/dev/null | head -3 | sed 's/^/      /'
        fi
        
        if [[ "$service" == "allocator" ]]; then
            local task_received=$(grep -iE "\[ALLOCATOR-RECEIVE\]|AllocateTask request" "$log_file" 2>/dev/null | wc -l || echo "0")
            local node_registered=$(grep -iE "\[ALLOCATOR-NODE-REGISTERED\]" "$log_file" 2>/dev/null | wc -l || echo "0")
            echo "    Allocator stats: Tasks received: $task_received, Nodes registered: $node_registered"
        elif [[ "$service" =~ ^scheduler-[0-9]+$ ]]; then
            local task_received=$(grep -iE "\[SCHEDULER-RECEIVE\]|AddTaskToQueue request" "$log_file" 2>/dev/null | wc -l || echo "0")
            local task_processed=$(grep -iE "\[SCHEDULER-SUCCESS\]" "$log_file" 2>/dev/null | wc -l || echo "0")
            echo "    Scheduler stats: Tasks received: $task_received, Tasks processed: $task_processed"
        fi
    else
        # Log file doesn't exist - server may not have written logs yet
        # Fallback: try to get logs from container (with timeout to prevent hanging)
        echo "  ⚠ Log file not found, attempting to collect from container (with timeout)..."
        local temp_file="/tmp/${service}_server.log.$$"
        if timeout 5 docker compose logs --no-color --tail 10000 "$service" > "$temp_file" 2>&1; then
            if [ -f "$temp_file" ] && [ -s "$temp_file" ]; then
                mv "$temp_file" "$log_file" 2>/dev/null || cp "$temp_file" "$log_file"
                chmod 644 "$log_file" 2>/dev/null || true
                chown "$USER:$USER" "$log_file" 2>/dev/null || true
                rm -f "$temp_file"
                local line_count=$(wc -l < "$log_file" 2>/dev/null || echo "0")
                echo "  ✓ Collected from container: $log_file ($line_count lines)"
            else
                echo "  ✗ Failed to collect logs from $service (empty or no logs)"
                rm -f "$temp_file"
            fi
        else
            echo "  ✗ Failed to collect logs from $service (timeout or container not found)"
            rm -f "$temp_file"
        fi
    fi
}

collect_logs "allocator" "$REPORT_DIR/allocator/logs"
collect_logs "scheduler-1" "$REPORT_DIR/scheduler/node1/logs"
collect_logs "scheduler-2" "$REPORT_DIR/scheduler/node2/logs"
collect_logs "scheduler-3" "$REPORT_DIR/scheduler/node3/logs"

echo "================================================================================"
echo "Server logs collected."
echo ""

# Stop all containers
echo "Stopping all containers..."
docker compose down

echo "Containers stopped. In-memory state cleared."
echo "Note: Model files persist in ./models/ directory"

# Verify report files exist
echo ""
echo "Verifying report files..."

VERIFICATION_FAILED=0

check_file() {
    local file="$1"
    local description="$2"
    if [ -f "$file" ]; then
        echo "✓ $description: $file"
    else
        echo "✗ Missing: $description: $file"
        VERIFICATION_FAILED=1
    fi
}

# Check simulation reports
check_file "$REPORT_DIR/simulation/report.txt" "Simulation report (text)"
check_file "$REPORT_DIR/simulation/report.json" "Simulation report (JSON)"

# Check scheduler reports (optional - may not be generated yet)
for node in node1 node2 node3; do
    if [ -f "$REPORT_DIR/scheduler/$node/report.txt" ]; then
        check_file "$REPORT_DIR/scheduler/$node/report.txt" "Scheduler $node report (text)"
    fi
    if [ -f "$REPORT_DIR/scheduler/$node/report.json" ]; then
        check_file "$REPORT_DIR/scheduler/$node/report.json" "Scheduler $node report (JSON)"
    fi
    # Check scheduler logs (always generated if container ran)
    if [ -f "$REPORT_DIR/scheduler/$node/logs/server.log" ]; then
        echo "✓ Scheduler $node logs: $REPORT_DIR/scheduler/$node/logs/server.log"
        scheduler_errors=$(grep -iE "error|fatal|panic" "$REPORT_DIR/scheduler/$node/logs/server.log" 2>/dev/null | wc -l || echo "0")
        scheduler_tasks=$(grep -iE "\[SCHEDULER-RECEIVE\]" "$REPORT_DIR/scheduler/$node/logs/server.log" 2>/dev/null | wc -l || echo "0")
        scheduler_success=$(grep -iE "\[SCHEDULER-SUCCESS\]" "$REPORT_DIR/scheduler/$node/logs/server.log" 2>/dev/null | wc -l || echo "0")
        if [ "$scheduler_errors" -gt 0 ]; then
            echo "  ⚠ Scheduler $node has $scheduler_errors errors"
        fi
        echo "    Scheduler $node activity: $scheduler_tasks tasks received, $scheduler_success tasks processed"
    fi
done

# Check allocator logs
if [ -f "$REPORT_DIR/allocator/logs/server.log" ]; then
    echo "✓ Allocator logs: $REPORT_DIR/allocator/logs/server.log"
    allocator_errors=$(grep -iE "error|fatal|panic" "$REPORT_DIR/allocator/logs/server.log" 2>/dev/null | wc -l || echo "0")
    allocator_tasks=$(grep -iE "\[ALLOCATOR-RECEIVE\]" "$REPORT_DIR/allocator/logs/server.log" 2>/dev/null | wc -l || echo "0")
    allocator_nodes=$(grep -iE "\[ALLOCATOR-NODE-REGISTERED\]" "$REPORT_DIR/allocator/logs/server.log" 2>/dev/null | wc -l || echo "0")
    if [ "$allocator_errors" -gt 0 ]; then
        echo "  ⚠ Allocator has $allocator_errors errors"
    fi
    echo "    Allocator activity: $allocator_tasks tasks received, $allocator_nodes nodes registered"
fi

# Check allocator reports (optional)
if [ -f "$REPORT_DIR/allocator/report.txt" ]; then
    check_file "$REPORT_DIR/allocator/report.txt" "Allocator report (text)"
fi
if [ -f "$REPORT_DIR/allocator/report.json" ]; then
    check_file "$REPORT_DIR/allocator/report.json" "Allocator report (JSON)"
fi

# Check metadata
check_file "$REPORT_DIR/metadata.json" "Run metadata"
check_file "$REPORT_DIR/configs/allocator/config.yaml" "Config snapshot (allocator)"
check_file "$REPORT_DIR/configs/simulation/application.yml" "Config snapshot (simulation)"

if [ $VERIFICATION_FAILED -eq 0 ]; then
    echo ""
    echo "================================================================================"
    echo "Run verification: PASSED"
    echo "Reports available in: $REPORT_DIR"
    echo "================================================================================"
else
    echo ""
    echo "================================================================================"
    echo "Run verification: FAILED (some reports missing)"
    echo "Check logs for details"
    echo "================================================================================"
    exit 1
fi

