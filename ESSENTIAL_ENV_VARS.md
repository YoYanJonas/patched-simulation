# Essential Environment Variables (Runtime Settings Only)
# ======================================================
# These are the ONLY environment variables that should be set at runtime.
# All other configuration comes from YAML files (application.yml).

# Path Configuration
CONFIG_DIR          # Path to config directory containing application.yml
REPORT_DIR          # Path where simulation reports are saved

# Service Host/Port Overrides (optional - can override YAML settings)
ALLOCATION_HOST     # Allocation service hostname (default: localhost)
ALLOCATION_PORT     # Allocation service port (default: 50051)
SCHEDULER_1_HOST    # Scheduler 1 hostname (default: localhost)
SCHEDULER_1_PORT    # Scheduler 1 port (default: 50052)
SCHEDULER_2_HOST    # Scheduler 2 hostname (default: localhost)
SCHEDULER_2_PORT    # Scheduler 2 port (default: 50053)
SCHEDULER_3_HOST    # Scheduler 3 hostname (default: localhost)
SCHEDULER_3_PORT    # Scheduler 3 port (default: 50054)

# RL Server Overrides (optional - can override YAML settings)
CLOUD_RL_SERVER_HOST        # Cloud RL server hostname
CLOUD_RL_SERVER_PORT        # Cloud RL server port
PLACEMENT_RL_SERVER_HOST    # Placement RL server hostname
PLACEMENT_RL_SERVER_PORT    # Placement RL server port
EXTERNAL_TASK_SERVER_HOST   # External task RL server hostname
EXTERNAL_TASK_SERVER_PORT   # External task RL server port

# Note: All other settings (enabled flags, rates, delays, etc.) should be
# configured in the YAML file: config/{SCENARIO}/simulation/application.yml
