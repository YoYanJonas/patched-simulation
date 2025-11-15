package org.patch.utils;

/**
 * Immutable container for deferred CloudSim event information.
 * 
 * <p>
 * This class stores event information with absolute event time (not delay)
 * to ensure correct temporal ordering when events are processed after being
 * deferred from async gRPC callbacks.
 * </p>
 * 
 * <p>
 * Key Design:
 * </p>
 * <ul>
 * <li>Stores <b>absolute event time</b> (not delay) - critical for
 * correctness</li>
 * <li>Captures simulation clock at callback time - for debugging</li>
 * <li>Immutable - thread-safe by design</li>
 * </ul>
 * 
 * @author Younes Shafiee
 * @version 1.0.0
 * @since 1.0.0
 */
public class DeferredEventInfo {
    private final int src;
    private final int dest;
    private final double absoluteEventTime; // CRITICAL: Absolute time, not delay
    private final int tag;
    private final Object data;
    private final double capturedClock; // For debugging

    /**
     * Creates a new DeferredEventInfo instance.
     * 
     * @param src               Source entity ID
     * @param dest              Destination entity ID
     * @param absoluteEventTime Absolute simulation time when event should occur
     * @param tag               Event tag
     * @param data              Event data
     * @param capturedClock     Simulation clock value when callback completed (for
     *                          debugging)
     */
    public DeferredEventInfo(int src, int dest, double absoluteEventTime, int tag, Object data, double capturedClock) {
        this.src = src;
        this.dest = dest;
        this.absoluteEventTime = absoluteEventTime;
        this.tag = tag;
        this.data = data;
        this.capturedClock = capturedClock;
    }

    /**
     * Gets the source entity ID.
     * 
     * @return Source entity ID
     */
    public int getSrc() {
        return src;
    }

    /**
     * Gets the destination entity ID.
     * 
     * @return Destination entity ID
     */
    public int getDest() {
        return dest;
    }

    /**
     * Gets the absolute event time (when event should occur in simulation).
     * 
     * @return Absolute event time
     */
    public double getAbsoluteEventTime() {
        return absoluteEventTime;
    }

    /**
     * Gets the event tag.
     * 
     * @return Event tag
     */
    public int getTag() {
        return tag;
    }

    /**
     * Gets the event data.
     * 
     * @return Event data
     */
    public Object getData() {
        return data;
    }

    /**
     * Gets the captured clock value (for debugging).
     * 
     * @return Simulation clock value when callback completed
     */
    public double getCapturedClock() {
        return capturedClock;
    }

    @Override
    public String toString() {
        return String.format("DeferredEventInfo{src=%d, dest=%d, eventTime=%.6f, tag=%d, capturedClock=%.6f}",
                src, dest, absoluteEventTime, tag, capturedClock);
    }
}
