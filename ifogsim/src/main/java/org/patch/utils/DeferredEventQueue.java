package org.patch.utils;

import org.cloudbus.cloudsim.core.CloudSim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Thread-safe deferred event queue for CloudSim events from async gRPC
 * callbacks.
 * 
 * <p>
 * This class prevents ConcurrentModificationException by buffering events from
 * async gRPC callbacks and processing them safely at the end of each simulation
 * tick.
 * </p>
 * 
 * <p>
 * Key Features:
 * </p>
 * <ul>
 * <li><b>Thread-Safe Storage</b>: Uses ConcurrentLinkedQueue for concurrent
 * access</li>
 * <li><b>Absolute Time Storage</b>: Stores events with absolute event time (not
 * delay)</li>
 * <li><b>Temporal Ordering</b>: Sorts events by time before processing</li>
 * <li><b>Past Event Handling</b>: Validates and adjusts past events</li>
 * <li><b>Cleanup Support</b>: Clears queue on simulation end</li>
 * </ul>
 * 
 * @author Younes Shafiee
 * @version 1.0.0
 * @since 1.0.0
 */
public class DeferredEventQueue {
    private static final Logger logger = Logger.getLogger(DeferredEventQueue.class.getName());

    // Thread-safe queue for deferred events
    private static final Queue<DeferredEventInfo> deferredEvents = new ConcurrentLinkedQueue<>();

    // Flag to prevent recursive processing
    private static final AtomicBoolean processingDeferred = new AtomicBoolean(false);

    /**
     * Adds an event to the deferred queue (thread-safe).
     * Called from async gRPC callbacks.
     * 
     * <p>
     * CRITICAL: This method captures the simulation clock at callback time
     * and calculates absolute event time to ensure correct temporal ordering.
     * </p>
     * 
     * <p>
     * Data Validation:
     * </p>
     * <ul>
     * <li>Entity IDs must be non-negative</li>
     * <li>Delay must be non-negative (will be validated by
     * ensureValidEventDelay)</li>
     * <li>Event data is validated (not null for critical events)</li>
     * </ul>
     * 
     * @param src   Source entity ID
     * @param dest  Destination entity ID
     * @param delay Delay from current simulation time
     * @param tag   Event tag
     * @param data  Event data (must not be null for critical events)
     * @throws IllegalArgumentException if validation fails
     */
    public static void addDeferredEvent(int src, int dest, double delay, int tag, Object data) {
        // Validate entity IDs
        if (src < 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid source entity ID: %d (must be non-negative)", src));
        }
        if (dest < 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid destination entity ID: %d (must be non-negative)", dest));
        }

        // Validate delay (ensureValidEventDelay will handle negative/zero delays)
        if (Double.isNaN(delay) || Double.isInfinite(delay)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid delay: %.6f (must be finite)", delay));
        }

        // Validate data for critical events (gRPC response events should have pending
        // request)
        if (data == null && isCriticalEvent(tag)) {
            logger.warning(String.format(
                    "[DEFERRED-EVENT-VALIDATION] Critical event (tag=%d) has null data - this may cause issues",
                    tag));
        }

        // CRITICAL: Capture clock at callback time
        double capturedClock = CloudSim.clock();

        // Validate captured clock
        if (capturedClock < 0 || Double.isNaN(capturedClock) || Double.isInfinite(capturedClock)) {
            throw new IllegalStateException(String.format(
                    "Invalid simulation clock: %.6f (captured at callback time)", capturedClock));
        }

        // Calculate absolute event time
        double validDelay = NetworkLatencyConverter.ensureValidEventDelay(delay);
        double absoluteEventTime = capturedClock + validDelay;

        // Validate calculated event time
        if (Double.isNaN(absoluteEventTime) || Double.isInfinite(absoluteEventTime)) {
            throw new IllegalStateException(String.format(
                    "Invalid calculated event time: %.6f (capturedClock=%.6f, validDelay=%.6f)",
                    absoluteEventTime, capturedClock, validDelay));
        }

        // Create event info
        DeferredEventInfo eventInfo = new DeferredEventInfo(
                src, dest, absoluteEventTime, tag, data, capturedClock);

        // Add to thread-safe queue
        boolean added = deferredEvents.offer(eventInfo);
        if (!added) {
            throw new IllegalStateException("Failed to add event to deferred queue (queue full)");
        }

        logger.fine(String.format(
                "[DEFERRED-EVENT-ADDED] Added deferred event: Tag=%d, EventTime=%.6f, CapturedClock=%.6f, Delay=%.6f, Src=%d, Dest=%d, DataType=%s",
                tag, absoluteEventTime, capturedClock, validDelay, src, dest,
                data != null ? data.getClass().getSimpleName() : "null"));
    }

    /**
     * Checks if an event tag represents a critical event that requires data.
     * 
     * @param tag Event tag
     * @return true if event is critical and requires data
     */
    private static boolean isCriticalEvent(int tag) {
        // Critical events are gRPC response events that need pending request data
        return tag == org.patch.utils.ExtendedFogEvents.GRPC_SCHEDULER_RESPONSE
                || tag == org.patch.utils.ExtendedFogEvents.GRPC_ALLOCATOR_RESPONSE
                || tag == org.patch.utils.ExtendedFogEvents.GRPC_ALLOCATOR_OUTCOME_RESPONSE;
    }

    /**
     * Processes all deferred events and adds them to CloudSim's future queue.
     * Called at the end of CloudSim.runClockTick().
     * 
     * <p>
     * CRITICAL: Must be called AFTER iteration completes to prevent
     * ConcurrentModificationException, and BEFORE return to ensure events
     * are added in the same tick.
     * </p>
     */
    public static void processDeferredEvents() {
        // Prevent recursive calls
        if (processingDeferred.get()) {
            logger.warning("[DEFERRED-EVENT-PROCESS] Recursive call detected, skipping");
            return;
        }

        processingDeferred.set(true);
        try {
            // CRITICAL: Copy to list first (thread-safe)
            List<DeferredEventInfo> eventsToProcess = new ArrayList<>();
            DeferredEventInfo event;
            while ((event = deferredEvents.poll()) != null) {
                eventsToProcess.add(event);
            }

            if (eventsToProcess.isEmpty()) {
                return;
            }

            // CRITICAL: Sort by absolute event time (temporal ordering)
            eventsToProcess.sort(Comparator.comparingDouble(DeferredEventInfo::getAbsoluteEventTime));

            // Process each event
            double currentClock = CloudSim.clock();
            int processedCount = 0;
            int adjustedCount = 0;

            for (DeferredEventInfo eventInfo : eventsToProcess) {
                try {
                    // Validate event info before processing
                    validateEventInfo(eventInfo, currentClock);

                    double eventTime = eventInfo.getAbsoluteEventTime();

                    // CRITICAL: Validate event time (prevent past events)
                    if (eventTime < currentClock) {
                        // Event is in past - adjust to current time + minimum delay
                        double minDelay = CloudSim.getMinTimeBetweenEvents();
                        eventTime = currentClock + minDelay;
                        adjustedCount++;

                        logger.warning(String.format(
                                "[DEFERRED-EVENT-ADJUSTED] Event time adjusted: original=%.6f, currentClock=%.6f, new=%.6f, tag=%d",
                                eventInfo.getAbsoluteEventTime(), currentClock, eventTime, eventInfo.getTag()));
                    }

                    // Validate data is still valid (check for null on critical events)
                    Object eventData = eventInfo.getData();
                    if (eventData == null && isCriticalEvent(eventInfo.getTag())) {
                        logger.warning(String.format(
                                "[DEFERRED-EVENT-PROCESS] Critical event (tag=%d) has null data at processing time - skipping",
                                eventInfo.getTag()));
                        continue; // Skip this event
                    }

                    // Add to CloudSim's future queue (safe now - iteration complete)
                    // Use sendDirect with absolute time (creates SimEvent internally)
                    CloudSim.sendDirect(
                            eventInfo.getSrc(),
                            eventInfo.getDest(),
                            eventTime, // CRITICAL: Use absolute time
                            eventInfo.getTag(),
                            eventData);
                    processedCount++;
                } catch (Exception e) {
                    logger.severe(String.format(
                            "[DEFERRED-EVENT-PROCESS-ERROR] Failed to process deferred event: tag=%d, src=%d, dest=%d, error=%s",
                            eventInfo.getTag(), eventInfo.getSrc(), eventInfo.getDest(), e.getMessage()));
                    // Continue processing other events
                }
            }

            if (adjustedCount > 0) {
                logger.warning(String.format(
                        "[DEFERRED-EVENT-PROCESSED] Processed %d events, %d adjusted (past events) at clock=%.6f",
                        processedCount, adjustedCount, currentClock));
            } else {
                logger.fine(String.format(
                        "[DEFERRED-EVENT-PROCESSED] Processed %d events at clock=%.6f",
                        processedCount, currentClock));
            }

        } finally {
            processingDeferred.set(false);
        }
    }

    /**
     * Checks if there are any deferred events waiting to be processed.
     * 
     * @return true if there are deferred events, false otherwise
     */
    public static boolean hasDeferredEvents() {
        return !deferredEvents.isEmpty();
    }

    /**
     * Gets the number of deferred events waiting to be processed.
     * 
     * @return Number of deferred events
     */
    public static int getDeferredEventCount() {
        return deferredEvents.size();
    }

    /**
     * Clears all deferred events (for cleanup on simulation end).
     */
    public static void clear() {
        int count = deferredEvents.size();
        deferredEvents.clear();
        logger.info(String.format("[DEFERRED-EVENT-CLEARED] Cleared %d deferred events", count));
    }

    /**
     * Validates event info before processing.
     * 
     * @param eventInfo    Event info to validate
     * @param currentClock Current simulation clock
     * @throws IllegalArgumentException if validation fails
     */
    private static void validateEventInfo(DeferredEventInfo eventInfo, double currentClock) {
        if (eventInfo == null) {
            throw new IllegalArgumentException("Event info is null");
        }

        // Validate entity IDs
        if (eventInfo.getSrc() < 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid source entity ID: %d", eventInfo.getSrc()));
        }
        if (eventInfo.getDest() < 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid destination entity ID: %d", eventInfo.getDest()));
        }

        // Validate event time
        double eventTime = eventInfo.getAbsoluteEventTime();
        if (Double.isNaN(eventTime) || Double.isInfinite(eventTime)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid event time: %.6f", eventTime));
        }

        // Validate captured clock
        double capturedClock = eventInfo.getCapturedClock();
        if (capturedClock < 0 || Double.isNaN(capturedClock) || Double.isInfinite(capturedClock)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid captured clock: %.6f", capturedClock));
        }

        // Validate captured clock is not in future (shouldn't happen, but check anyway)
        if (capturedClock > currentClock + 1.0) {
            logger.warning(String.format(
                    "[DEFERRED-EVENT-VALIDATION] Captured clock (%.6f) is significantly ahead of current clock (%.6f) - possible clock issue",
                    capturedClock, currentClock));
        }
    }
}
