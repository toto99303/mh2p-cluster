/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom;

/** Relative OEM wheel steps, bounded independently of completed zoom history.
 * Pure memory operations: safe to call from the stock wheel callback.
 * The future native sender must recheck generation and display ownership.
 */
public final class Mh2pWheelQueue {
    private static final int MAX_CALLBACK = 16, MAX_PENDING = 8;
    private static final long MAX_AGE = 1000L, INTERVAL = 150L;
    private long session, generation, sequence, lastInput, lastSent, lastNow;
    private boolean owned, haveClock, haveSent;
    private int pending;

    public static final class Event {
        public final long session, generation, sequence, capturedAt;
        public final int steps;
        private Event(long session, long generation, long sequence, long capturedAt, int steps) {
            this.session = session; this.generation = generation;
            this.sequence = sequence; this.capturedAt = capturedAt; this.steps = steps;
        }
    }

    /** session is a connection identity, never a file left over from last boot. */
    public synchronized void presentation(long session, boolean confirmedAaMap) {
        boolean next = session > 0 && confirmedAaMap;
        if (this.session != session || owned != next) {
            generation++; pending = 0; haveSent = false;
        }
        this.session = session; owned = next;
    }
    public synchronized boolean offer(int relativeSteps, long now) {
        if (!clock(now) || !owned || relativeSteps == 0
                || relativeSteps < -MAX_CALLBACK || relativeSteps > MAX_CALLBACK) return false;
        int next = pending + relativeSteps;
        pending = Math.max(-MAX_PENDING, Math.min(MAX_PENDING, next));
        lastInput = now;
        return true;
    }
    public synchronized Event poll(long now) {
        if (!clock(now) || !owned || pending == 0) return null;
        if (now-lastInput > MAX_AGE) { pending = 0; return null; }
        if (haveSent && now-lastSent < INTERVAL) return null;
        if (sequence == Long.MAX_VALUE) { cancel(); return null; }
        int step = pending > 0 ? 1 : -1;
        pending -= step; lastSent = now; haveSent = true;
        return new Event(session, generation, ++sequence, lastInput, step);
    }
    public synchronized boolean isCurrent(Event event, long now) {
        return event != null && clock(now) && owned && session == event.session
            && generation == event.generation && now >= event.capturedAt
            && now-event.capturedAt <= MAX_AGE;
    }
    public synchronized void cancel() { generation++; pending = 0; owned = false; }
    public synchronized long generation() { return generation; }
    public synchronized boolean hasPending() { return owned && pending != 0; }
    private boolean clock(long now) {
        if (now < 0 || (haveClock && now < lastNow)) {
            cancel(); lastNow = now; haveClock = true; return false;
        }
        lastNow = now; haveClock = true; return true;
    }
}
