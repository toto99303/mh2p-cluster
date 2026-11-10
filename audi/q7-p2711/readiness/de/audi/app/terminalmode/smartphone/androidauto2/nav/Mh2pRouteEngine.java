/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav;

/** Serialized background reconciliation. Intent callbacks perform no I/O. */
public final class Mh2pRouteEngine {
    public interface Backend {
        Mh2pRoutePolicy.State capture() throws Exception;
        void select(int[] layers, int[] expectedOem) throws Exception;
        void release() throws Exception;
        void log(String text);
    }
    private final Backend backend;
    private final Object pollLock = new Object();
    private long version;
    private boolean wanted, claimed, retiring, owned, releasing, blocked;
    private boolean admission;
    private int[] desired;
    private long requestedAt, releaseAt, lastNow;
    private int releases;
    private String lastMessage;

    public Mh2pRouteEngine(Backend backend) { this.backend = backend; }

    public synchronized boolean canPrepare() {
        return admission && !owned && !releasing && !retiring;
    }
    public synchronized boolean isOwned() { return owned; }
    /** Invalidate old render work as soon as a new prepare is queued. */
    public synchronized void preparePending() { wanted = false; blocked = false; version++; }
    public synchronized void command(String command) {
        if (command.startsWith("prepare") || command.startsWith("start")) {
            claimed = true; retiring = false; blocked = false;
            wanted = command.startsWith("start"); version++;
        } else if ("resume".equals(command)) {
            wanted = claimed && !retiring; version++;
        } else if ("pause".equals(command) || "stop".equals(command)) {
            wanted = false; if ("stop".equals(command)) retiring = claimed; version++;
        }
    }
    public synchronized void cancel() { wanted = false; version++; }

    public void poll(long now) {
        synchronized (pollLock) { reconcile(now); }
    }

    private void reconcile(long now) {
        long seen;
        boolean render;
        synchronized (this) {
            if (lastNow != 0 && now < lastNow) { wanted = false; blocked = true; version++; }
            lastNow = now; seen = version; render = wanted && !blocked;
        }
        // Disconnect/prepare gets first chance to release, before any query.
        if (!render && isOwned()) requestRelease(now, "video not selected");
        Mh2pRoutePolicy.State s;
        try { s = backend.capture(); }
        catch (Throwable failure) {
            synchronized (this) { admission = false; }
            say("QUERY_FAILED " + failure);
            if (isOwned()) requestRelease(now, "query failed");
            return;
        }
        synchronized (this) {
            if (retiring && !s.aaRegistered) { retiring = false; claimed = false; }
            admission = (claimed || !s.aaRegistered) && !retiring;
            if (seen != version) {
                // A stop during a query cannot cause a stale selection.
                if (!wanted && owned) { /* release below, outside this lock */ }
                else return;
            }
        }
        if (seen != currentVersion()) {
            if (isOwned()) requestRelease(now, "intent changed during query");
            return;
        }
        if (isReleasing()) {
            if (!Mh2pRoutePolicy.contains(s.actual, Mh2pRoutePolicy.AA)
                    && Mh2pRoutePolicy.equal(s.actual, s.oem)) {
                synchronized (this) { owned = false; releasing = false; desired = null; releases = 0; }
                say("OEM_CONFIRMED layers=" + Mh2pRoutePolicy.list(s.actual));
            } else if (now - releaseAt >= 1500L && releases < 3) {
                requestRelease(now, "waiting for OEM composition");
            } else if (releases >= 3 && now - releaseAt >= 1500L) {
                say("RESTORE_UNCONFIRMED use stock dmdt sb 4 or MMI restart");
            }
            return; // Fresh AA work waits for the release observation barrier.
        }
        if (!render) { say("OEM_WAIT video inactive"); return; }
        int[] next = Mh2pRoutePolicy.aaLayers(s.oem);
        boolean safe = s.clusterGeometry && s.nativeSource && s.aaSource && next != null;
        synchronized (this) { safe = safe && claimed && !retiring && !blocked; }
        if (!safe) {
            say("WAIT_SOURCE_OR_POLICY oem=" + Mh2pRoutePolicy.list(s.oem));
            if (isOwned()) requestRelease(now, "source or supported policy unavailable");
            return;
        }
        if (isOwned() && Mh2pRoutePolicy.equal(desired, next)) {
            if (Mh2pRoutePolicy.equal(s.actual, next)) {
                say("AA_CONFIRMED layers=" + Mh2pRoutePolicy.list(next));
            } else if (now - requestedAt >= 2000L) {
                synchronized (this) { blocked = true; }
                say("SELECT_UNCONFIRMED falling back to OEM");
                requestRelease(now, "selection was not observed");
            }
            return;
        }
        // With no ownership, never replace a pre-existing/debug composition.
        if (!isOwned() && !Mh2pRoutePolicy.equal(s.actual, s.oem)) {
            say("WAIT_CURRENT_OEM actual=" + Mh2pRoutePolicy.list(s.actual));
            return;
        }
        synchronized (this) {
            if (seen != version || !wanted || blocked) return;
            owned = true; desired = Mh2pRoutePolicy.copy(next); requestedAt = now;
        }
        try {
            backend.select(next, s.oem);
            say("AA_REQUESTED layers=" + Mh2pRoutePolicy.list(next));
        } catch (Throwable failure) {
            synchronized (this) { blocked = true; }
            say("SELECT_FAILED " + failure);
            requestRelease(now, "possibly partial selection");
            return;
        }
        // Intent callbacks did not wait for the process; retire a late result.
        if (seen != currentVersion()) requestRelease(now, "intent changed during selection");
    }

    private synchronized long currentVersion() { return version; }
    private synchronized boolean isReleasing() { return releasing; }
    private void requestRelease(long now, String reason) {
        synchronized (this) {
            if (!owned || (releasing && (now - releaseAt < 1500L || releases >= 3))) return;
            releasing = true; releaseAt = now; releases++;
        }
        try { backend.release(); say("OEM_REQUESTED reason=" + reason); }
        catch (Throwable failure) { say("RELEASE_FAILED " + failure); }
    }
    public void shutdown() {
        cancel();
        synchronized (pollLock) {
            if (isOwned()) {
                try { backend.release(); } catch (Throwable failure) { say("SHUTDOWN_RELEASE_FAILED " + failure); }
            }
        }
    }
    private void say(String message) {
        if (!message.equals(lastMessage)) { lastMessage = message; backend.log("MH2P_AUTO_ROUTE " + message); }
    }
}
