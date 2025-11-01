#!/bin/bash
# prepare-run.sh: Prepare run directory and snapshot configs
# Usage: prepare-run.sh [SCENARIO_NAME] [RUN_ID]
#   SCENARIO_NAME: Name of scenario (default: rl-full-feature)
#   RUN_ID: Run identifier (default: auto-generated timestamp)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SCENARIO_NAME="${1:-rl-full-feature}"
RUN_ID="${2:-$(date -u +%Y-%m-%dT%H-%M-%S)}"

REPORT_DIR="$PROJECT_ROOT/reports/${RUN_ID}_${SCENARIO_NAME}"
CONFIG_SRC="$PROJECT_ROOT/config/${SCENARIO_NAME}"

echo "================================================================================"
echo "Preparing run: ${RUN_ID}_${SCENARIO_NAME}"
echo "================================================================================"

# Validate scenario exists
if [ ! -d "$CONFIG_SRC" ]; then
    echo "Error: Scenario directory not found: $CONFIG_SRC"
    exit 1
fi

# Create report directory structure
echo "Creating report directory structure..."
mkdir -p "$REPORT_DIR"/{simulation,scheduler/{node1,node2,node3},allocator,configs}

# Copy config snapshot
echo "Creating config snapshot..."
cp -r "$CONFIG_SRC"/* "$REPORT_DIR/configs/"

# Create metadata.json
GIT_SHA=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
GIT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")

cat > "$REPORT_DIR/metadata.json" <<EOF
{
  "run_id": "$RUN_ID",
  "scenario": "$SCENARIO_NAME",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "git_sha": "$GIT_SHA",
  "git_branch": "$GIT_BRANCH",
  "config_snapshot_path": "configs/"
}
EOF

echo "Report directory prepared: $REPORT_DIR"
echo "Config snapshot: $REPORT_DIR/configs/"
echo "Metadata: $REPORT_DIR/metadata.json"

# Export variables for use by other scripts
export RUN_ID
export SCENARIO_NAME
export REPORT_DIR

