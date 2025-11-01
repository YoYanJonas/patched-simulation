#!/bin/bash
# run-scenario.sh: Orchestrate N runs of a scenario
# Usage: 
#   Interactive: ./run-scenario.sh
#   With args:   ./run-scenario.sh [SCENARIO_NAME] [NUM_RUNS]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Discover available scenarios from config directory
discover_scenarios() {
    local config_dir="$PROJECT_ROOT/config"
    if [ ! -d "$config_dir" ]; then
        echo "Error: Config directory not found: $config_dir"
        return 1
    fi
    
    # Find directories that contain both allocator/ and simulation/ subdirectories
    local scenarios=()
    for dir in "$config_dir"/*; do
        if [ -d "$dir" ] && [ -d "$dir/allocator" ] && [ -d "$dir/simulation" ]; then
            scenarios+=("$(basename "$dir")")
        fi
    done
    
    printf '%s\n' "${scenarios[@]}"
}

# Interactive scenario selection
select_scenario_interactive() {
    local scenarios
    readarray -t scenarios < <(discover_scenarios)
    
    if [ ${#scenarios[@]} -eq 0 ]; then
        echo "Error: No valid scenarios found in config/ directory"
        exit 1
    fi
    
    echo "Available scenarios:"
    echo "==================="
    local i=1
    for scenario in "${scenarios[@]}"; do
        echo "  $i) $scenario"
        ((i++))
    done
    echo ""
    
    while true; do
        read -p "Select scenario (1-${#scenarios[@]} or name): " choice
        if [ -z "$choice" ]; then
            echo "Invalid choice. Please try again."
            continue
        fi
        
        # Check if it's a number
        if [[ "$choice" =~ ^[0-9]+$ ]]; then
            if [ "$choice" -ge 1 ] && [ "$choice" -le ${#scenarios[@]} ]; then
                echo "${scenarios[$((choice-1))]}"
                return 0
            else
                echo "Invalid number. Please select 1-${#scenarios[@]}."
                continue
            fi
        else
            # Check if it's a valid scenario name
            for scenario in "${scenarios[@]}"; do
                if [ "$scenario" = "$choice" ]; then
                    echo "$choice"
                    return 0
                fi
            done
            echo "Scenario '$choice' not found. Please try again."
            continue
        fi
    done
}

# Get number of runs interactively
get_num_runs_interactive() {
    while true; do
        read -p "Number of runs (default: 1): " num_runs
        if [ -z "$num_runs" ]; then
            echo "1"
            return 0
        fi
        
        if [[ "$num_runs" =~ ^[0-9]+$ ]] && [ "$num_runs" -gt 0 ]; then
            echo "$num_runs"
            return 0
        else
            echo "Invalid number. Please enter a positive integer."
            continue
        fi
    done
}

# Parse arguments or use interactive mode
if [ $# -ge 1 ]; then
    # Command-line mode
    SCENARIO_NAME="$1"
    NUM_RUNS="${2:-1}"
    
    # Validate scenario exists
    if [ ! -d "$PROJECT_ROOT/config/$SCENARIO_NAME" ]; then
        echo "Error: Scenario '$SCENARIO_NAME' not found in config/ directory"
        echo "Available scenarios:"
        discover_scenarios | sed 's/^/  - /'
        exit 1
    fi
    
    # Validate NUM_RUNS
    if ! [[ "$NUM_RUNS" =~ ^[0-9]+$ ]] || [ "$NUM_RUNS" -lt 1 ]; then
        echo "Error: Number of runs must be a positive integer"
        exit 1
    fi
else
    # Interactive mode
    echo "================================================================================"
    echo "Scenario Selection"
    echo "================================================================================"
    SCENARIO_NAME=$(select_scenario_interactive)
    echo ""
    NUM_RUNS=$(get_num_runs_interactive)
    echo ""
fi

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

