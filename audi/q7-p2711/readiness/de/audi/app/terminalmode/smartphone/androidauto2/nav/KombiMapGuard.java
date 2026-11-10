package de.audi.app.terminalmode.smartphone.androidauto2.nav;

import de.audi.app.terminalmode.IContext;
import de.audi.app.terminalmode.device.TMDevice;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Hides the native map only after verified MH2p cockpit readiness and
 * secondary video admission; preserves the existing disconnect hand-back.
 * Android Auto transport startup is never delayed or restarted here.
 */
public class KombiMapGuard implements Runnable, ServiceTrackerCustomizer {

    public static final String BUILD = "kombi-guard p2711-stock-handover-candidate2";

    private static final String MAP_CLUSTER_CLASS = "de.audi.tghu.navi.app.cluster.IMapClusterService";
    private static final String CLUSTER_CTL = "/tmp/cluster_ctl";
    private static final int CL_IDLE = 0;
    private static final int CL_PREPARED = 1;
    private static final int CL_RENDERING = 2;
    private static final int CL_PAUSED = 3;
    private static final long POLL_MS = 1000L;
    private static final long LOOKUP_RETRY_MS = 5000L;
    private static final long REAPPLY_MS = 10000L;
    private static final long SHOW_DELAY_MS = 1200L;
    private static final long RESHOW_MS = 5000L;
    // CockpitShot (Persist mod) takes a burst of cockpit screenshots when this appears.
    private static final String SHOT_FLAG = "/tmp/cockpit_shot";
    // After a released pause: prepare, then resume this much later.
    private static final long RESUME_AFTER_MS = 2500L;

    private final IContext context;
    private final AndroidAutoClusterIntegration integration;
    private final Mh2pMapHandover handover;
    private long observeAt;
    private Field stateField;
    private BundleContext bundleContext;
    private ServiceTracker tracker;
    private volatile Object mapClusterService;
    private String lastGateError;
    private long lastLookup = 0L;
    private boolean codingLogged = false;
    private boolean hidden = false;
    private boolean suspended = false;
    private long lastApply = 0L;
    private long showAt = 0L;
    // Second hand-back after an unplug, in case the first ran while the
    // AA cockpit window was still closing.
    private long reshowAt = 0L;
    private boolean splashPending = false;
    private int rebindStep = 0;
    private long rebindAt = 0L;
    private boolean stopSent = false;
    private boolean pausedReleased = false;

    private KombiMapGuard(IContext context, AndroidAutoClusterIntegration integration) {
        this.context = context;
        this.integration = integration;
        this.handover = new Mh2pMapHandover(integration);
    }

    /** Starts the guard thread. Never throws. */
    public static void start(IContext context, AndroidAutoClusterIntegration integration) {
        try {
            KombiMapGuard guard = new KombiMapGuard(context, integration);
            Thread t = new Thread(guard, "KombiMapGuard");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (Throwable t) {
            safeLog(integration, "KOMBI_MAP: guard not started: " + t);
        }
    }

    public void run() {
        log(BUILD + " started");
        log("MH2P_VIDEO_GATE WAIT initialized=0 or first_drawn=0; AA startup unchanged");
        try {
            stateField = AndroidAutoClusterIntegration.class.getDeclaredField("clusterState");
            stateField.setAccessible(true);
        } catch (Throwable t) {
            log("clusterState field not found, guard stopped: " + t);
            return;
        }
        openTracker();
        while (true) {
            try {
                tick();
            } catch (Throwable t) {
                log("tick error: " + t);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void tick() throws Exception {
        long now = System.currentTimeMillis();
        if ((mapClusterService == null) && now - lastLookup >= LOOKUP_RETRY_MS) {
            lastLookup = now;
            lookup();
        }
        if (!codingLogged) {
            logNavMapTransmissionMode();
        }
        try {
            Mh2pVideoGate.poll(integration, mapClusterService);
        } catch (Throwable failure) {
            String error = failure.toString();
            if (!error.equals(lastGateError)) {
                log("MH2P_VIDEO_GATE WAIT probe failed: " + error);
                lastGateError = error;
            }
        }
        // clusterState records the requested state, not proof of FIFO admission.
        // Until ready, do not suspend/hide the map we are waiting to initialize.
        if (!Mh2pVideoGate.isReady()) return;
        boolean rendering = stateField.getInt(integration) == CL_RENDERING;
        if (rendering && (!hidden || now - lastApply >= REAPPLY_MS)) {
            if (!hidden) {
                showAt = 0L;
                reshowAt = 0L;
                observeAt = 0L;
                log("AA video rendering, hiding native cockpit map through stock request wrapper");
            }
            hideNative(!hidden || !suspended);
            hidden = true;
            lastApply = now;
        } else if (!rendering && hidden) {
            log("AA video stopped, showing native cockpit map in " + SHOW_DELAY_MS + " ms");
            touch(SHOT_FLAG);
            hidden = false;
            showAt = now + SHOW_DELAY_MS;
        }
        if (showAt != 0L && now >= showAt) {
            showAt = 0L;
            if (!rendering && stateField.getInt(integration) != CL_PREPARED) {
                showNative();
                observeAt = now + 2000L;
                reshowAt = now + RESHOW_MS;
            }
        }
        if (reshowAt != 0L && now >= reshowAt) {
            reshowAt = 0L;
            if (!rendering && !hidden && stateField.getInt(integration) != CL_PREPARED) {
                log("showing native cockpit map again (state=" + stateField.getInt(integration) + ")");
                showNative();
                observeAt = now + 2000L;
            }
        }
        if (observeAt != 0L && now >= observeAt) {
            observeAt = 0L;
            if (!rendering && stateField.getInt(integration) != CL_PREPARED)
                handover.observe(mapClusterService);
        }
        if (splashPending) {
            int state = stateField.getInt(integration);
            if (rendering || state == CL_PREPARED) {
                splashPending = false;
                stopSent = false;
            } else if (state == CL_IDLE) {
                if (releaseStopSplash()) {
                    splashPending = false;
                    stopSent = false;
                }
            } else if (state == CL_PAUSED && !deviceActive()) {
                // Adapter pulled: AA only paused the video and the paused
                // h264 child keeps its window over the native map. Stop it,
                // then drop the stop splash; a later resume re-prepares.
                if (!stopSent) {
                    if (new File(CLUSTER_CTL).length() == 0 && mirror("stop")) {
                        stopSent = true;
                        pausedReleased = true;
                        log("AA paused with no active device, releasing the cockpit");
                    }
                } else if (releaseStopSplash()) {
                    splashPending = false;
                    stopSent = false;
                }
            }
        }
        if (rendering && pausedReleased && rebindStep == 0) {
            pausedReleased = false;
            log("AA video back after a released pause, re-preparing the cockpit window");
            if (mirror("prepare")) {
                rebindStep = 3;
                rebindAt = now + RESUME_AFTER_MS;
            }
        }
        if (rebindStep == 3 && now >= rebindAt && new File(CLUSTER_CTL).length() == 0) {
            mirror("resume");
            rebindStep = 0;
        }

    }

    /**
     * Sends a command through the integration's own sendMirrorCommand; for
     * "prepare" the command line comes from its buildH264PrepareCommand, so
     * mode, zoom and pan match what it sends at session start.
     */
    private boolean mirror(String what) {
        try {
            String cmd = what;
            if ("prepare".equals(what)) {
                Method b = AndroidAutoClusterIntegration.class.getDeclaredMethod("buildH264PrepareCommand", new Class[0]);
                b.setAccessible(true);
                cmd = (String) b.invoke(integration, new Object[0]);
            }
            Method m = AndroidAutoClusterIntegration.class.getDeclaredMethod("sendMirrorCommand", new Class[] { String.class });
            m.setAccessible(true);
            m.invoke(integration, new Object[] { cmd });
            log("rebind: sent '" + cmd + "'");
            return true;
        } catch (Throwable t) {
            log("rebind: could not send " + what + ": " + t);
            return false;
        }
    }

    private boolean deviceActive() {
        try {
            TMDevice d = context.getDeviceManager().getActiveDevice();
            return d != null && d.isValid();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Use stock request wrappers so renderer acknowledgements have matching state. */
    private void hideNative(boolean suspend) {
        boolean sent = handover.hide(mapClusterService, suspend);
        if (sent && suspend) suspended = true;
        callMapCluster("switchKombiMapToHiddenContext");
    }

    private void showNative() {
        // Restore native context first; a later context pause can otherwise
        // overwrite visibility that was set before these calls.
        callMapCluster("switchKombiMapToHiddenContext");
        callMapCluster("switchKombiMapToAShownContext");
        try {
            if (mapClusterService != null) {
                mapClusterService.getClass().getMethod("notifyViewSizeChanged", new Class[] { Boolean.TYPE })
                        .invoke(mapClusterService, new Object[] { Boolean.TRUE });
            }
        } catch (Throwable t) {
            log("notifyViewSizeChanged failed: " + t);
        }
        if (handover.restore(mapClusterService)) suspended = false;
        splashPending = true;
    }

    /**
     * On "stop" the cluster daemon posts a canim splash to displayable 33 with
     * no time limit and only removes it on the next "resume", so after an AA
     * unplug the native map stays covered (black cockpit until an MMI restart,
     * A6 C8 report 2026-11-04). "resume" with no h264 child kills just the
     * splash. Only after a real session end (IDLE), not a pause, and only when
     * the daemon has consumed the previous command (it truncates the file).
     */
    private boolean releaseStopSplash() {
        try {
            if (new File(CLUSTER_CTL).length() != 0) {
                return false;
            }
            FileOutputStream out = new FileOutputStream(CLUSTER_CTL);
            out.write("resume\n".getBytes());
            out.close();
            log("sent 'resume' to " + CLUSTER_CTL + " to drop the stop splash");
        } catch (Throwable t) {
            log("could not release stop splash: " + t);
        }
        return true;
    }

    private void callMapCluster(String method) {
        Object s = mapClusterService;
        if (s == null) {
            return;
        }
        try {
            Method m = s.getClass().getMethod(method, new Class[0]);
            m.invoke(s, new Object[0]);
        } catch (Throwable t) {
            log(method + " failed: " + t);
        }
    }

    private void touch(String path) {
        try {
            new FileOutputStream(path).close();
            log("wrote " + path);
        } catch (Throwable t) {
            log("could not write " + path + ": " + t);
        }
    }

    // ---- finding the services ------------------------------------------------

    private void openTracker() {
        try {
            bundleContext = context.getServiceManager().getBundleContext();
        } catch (Throwable t) {
            log("bundle context unavailable: " + t);
            return;
        }
        if (bundleContext == null) {
            log("bundle context is null; tracker not started");
            return;
        }
        try {
            tracker = new ServiceTracker(bundleContext, MAP_CLUSTER_CLASS, this);
            tracker.open();
            log("tracking " + MAP_CLUSTER_CLASS + " by name ("
                    + (mapClusterService != null ? "already registered" : "not registered yet, waiting") + ")");
        } catch (Throwable t) {
            log("ServiceTracker failed: " + t);
        }
    }

    public Object addingService(ServiceReference reference) {
        Object s = null;
        try {
            s = bundleContext.getService(reference);
        } catch (Throwable t) {
            log("getService failed: " + t);
        }
        if (s != null) {
            mapClusterService = s;
            log("IMapClusterService registered (tracker, class=" + s.getClass().getName() + ")");
        }
        return s;
    }

    public void modifiedService(ServiceReference reference, Object s) {
    }

    public void removedService(ServiceReference reference, Object s) {
        if (mapClusterService == s) {
            mapClusterService = null;
        }
        try {
            bundleContext.ungetService(reference);
        } catch (Throwable t) {
            // nothing to do
        }
        log("IMapClusterService unregistered");
    }

    private void lookup() {
        if (mapClusterService == null) {
            try {
                Class c = Class.forName(MAP_CLUSTER_CLASS);
                ServiceReference[] refs = context.getServiceManager().getServiceReferences(c);
                if (refs != null && refs.length > 0) {
                    mapClusterService = context.getServiceManager().getService(refs[0]);
                    if (mapClusterService != null) {
                        log("IMapClusterService found (service manager)");
                    }
                }
            } catch (Throwable t) {
                log("IMapClusterService lookup failed: " + t);
            }
        }
    }

    /** One log line with the car's coding: 3 = virtual cockpit (arrows go by video, not BAP). */
    private void logNavMapTransmissionMode() {
        try {
            Class c = Class.forName("de.audi.mib.system.ISysServices");
            ServiceReference[] refs = context.getServiceManager().getServiceReferences(c);
            if (refs == null || refs.length == 0) {
                return;
            }
            Object o = context.getServiceManager().getService(refs[0]);
            String[] chain = {"config", "rawCoding", "displayConfig", "getNavMapTransmissionMode"};
            for (int i = 0; o != null && i < chain.length; i++) {
                Method m = o.getClass().getMethod(chain[i], new Class[0]);
                m.setAccessible(true); // implementation classes may be package-private
                o = m.invoke(o, new Object[0]);
            }
            log("coding navMapTransmissionMode=" + o + " (3 = virtual cockpit)");
        } catch (Throwable t) {
            log("navMapTransmissionMode not readable: " + t);
        }
        codingLogged = true;
    }

    private void log(String msg) {
        safeLog(integration, "KOMBI_MAP: " + msg);
    }

    private static void safeLog(AndroidAutoClusterIntegration integration, String msg) {
        try {
            integration.logCluster(msg);
        } catch (Throwable t) {
            // logging must never take Android Auto down
        }
    }
}
