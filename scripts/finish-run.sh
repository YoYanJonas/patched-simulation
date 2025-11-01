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
done

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

