#!/bin/bash
# run-scenario.sh: Orchestrate N runs of a scenario
# Usage: run-scenario.sh [SCENARIO_NAME] [NUM_RUNS]
#   SCENARIO_NAME: Name of scenario (default: rl-full-feature)
#   NUM_RUNS: Number of runs to execute (default: 1)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SCENARIO_NAME="${1:-rl-full-feature}"
NUM_RUNS="${2:-1}"

echo "================================================================================"
echo "Running scenario: $SCENARIO_NAME"
echo "Number of runs: $NUM_RUNS"
echo "================================================================================"
echo ""

SUCCESS_COUNT=0
FAILED_COUNT=0

for i in $(seq 1 $NUM_RUNS); do
    echo ""
    echo "================================================================================"
    echo "Run $i of $NUM_RUNS"
    echo "================================================================================"
    
    # Generate RUN_ID for this run
    RUN_ID=$(date -u +%Y-%m-%dT%H-%M-%S)-run${i}
    
    # Prepare run
    if ! "$SCRIPT_DIR/prepare-run.sh" "$SCENARIO_NAME" "$RUN_ID"; then
        echo "Failed to prepare run $i"
        FAILED_COUNT=$((FAILED_COUNT + 1))
        continue
    fi
    
    # Start and run simulation
    if "$SCRIPT_DIR/start-run.sh" "$SCENARIO_NAME" "$RUN_ID"; then
        # Finish and verify
        if "$SCRIPT_DIR/finish-run.sh" "$SCENARIO_NAME" "$RUN_ID"; then
            SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
            echo "✓ Run $i completed successfully"
        else
            FAILED_COUNT=$((FAILED_COUNT + 1))
            echo "✗ Run $i verification failed"
        fi
    else
        FAILED_COUNT=$((FAILED_COUNT + 1))
        echo "✗ Run $i simulation failed"
        # Still try to finish (stop containers)
        "$SCRIPT_DIR/finish-run.sh" "$SCENARIO_NAME" "$RUN_ID" || true
    fi
    
    # Brief pause between runs (models persist, containers restart fresh)
    if [ $i -lt $NUM_RUNS ]; then
        echo "Waiting 2 seconds before next run..."
        sleep 2
    fi
done

echo ""
echo "================================================================================"
echo "Scenario execution summary: $SCENARIO_NAME"
echo "================================================================================"
echo "Total runs: $NUM_RUNS"
echo "Successful: $SUCCESS_COUNT"
echo "Failed: $FAILED_COUNT"
echo ""
echo "All reports: $PROJECT_ROOT/reports/"
echo "Model files: $PROJECT_ROOT/models/"
echo ""
if [ $FAILED_COUNT -eq 0 ]; then
    echo "✓ All runs completed successfully!"
    exit 0
else
    echo "✗ Some runs failed. Check logs for details."
    exit 1
fi

