package org.patch.utils;

/**
 * Extended FogEvents for gRPC communication
 * Follows patching paradigm - extends iFogSim's event system without modifying
 * original
 */
public class ExtendedFogEvents {

    // New event types for gRPC operations (starting from 1001 to avoid conflicts
    // with iFogSim's BASE=50)
    public static final int ALLOC_REQUEST_SENT = 1001; // Before calling gRPC allocation service
    public static final int ALLOC_RESPONSE_RECEIVED = 1002; // When allocation service responds
    public static final int SCHEDULER_REQUEST_SENT = 1003; // Before calling gRPC scheduler service
    public static final int SCHEDULER_CACHE_HIT = 1004; // When scheduler returns cached result
    public static final int SCHEDULER_CACHE_MISS = 1005; // When scheduler processes normally
    public static final int TASK_COMPLETE = 1006; // When task execution finishes
    public static final int METRICS_COLLECTION = 1007; // For periodic metrics gathering
    public static final int SCHEDULER_ERROR = 1008; // When scheduler call fails
    public static final int ALLOC_ERROR = 1009; // When allocation call fails
    public static final int TASK_FORWARDED = 1010; // When task is forwarded from cloud to fog node
    public static final int TASK_COMPLETION_CHECK = 1011; // For periodic task completion checking

    /**
     * Get event name for logging/debugging
     */
    public static String getEventName(int eventType) {
        switch (eventType) {
            case ALLOC_REQUEST_SENT:
                return "ALLOC_REQUEST_SENT";
            case ALLOC_RESPONSE_RECEIVED:
                return "ALLOC_RESPONSE_RECEIVED";
            case SCHEDULER_REQUEST_SENT:
                return "SCHEDULER_REQUEST_SENT";
            case SCHEDULER_CACHE_HIT:
                return "SCHEDULER_CACHE_HIT";
            case SCHEDULER_CACHE_MISS:
                return "SCHEDULER_CACHE_MISS";
            case TASK_COMPLETE:
                return "TASK_COMPLETE";
            case METRICS_COLLECTION:
                return "METRICS_COLLECTION";
            case SCHEDULER_ERROR:
                return "SCHEDULER_ERROR";
            case ALLOC_ERROR:
                return "ALLOC_ERROR";
            case TASK_FORWARDED:
                return "TASK_FORWARDED";
            case TASK_COMPLETION_CHECK:
                return "TASK_COMPLETION_CHECK";
            default:
                return "UNKNOWN_EVENT";
        }
    }
}
