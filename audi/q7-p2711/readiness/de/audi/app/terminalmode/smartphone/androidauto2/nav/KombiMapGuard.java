/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav;
import de.audi.app.terminalmode.IContext;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import java.lang.reflect.Method;

/** Read-only native-map readiness plus background source62 composition routing.
 * Native33 stays running; no suspend/hide/wake or transport restart heuristics.
 */
public class KombiMapGuard implements Runnable, ServiceTrackerCustomizer {
    public static final String BUILD = "kombi-guard p2711-auto-route62-candidate4";
    private static final String MAP_CLUSTER_CLASS = "de.audi.tghu.navi.app.cluster.IMapClusterService";
    private final IContext context;
    private final AndroidAutoClusterIntegration integration;
    private BundleContext bundleContext;
    private ServiceTracker tracker;
    private volatile Object mapClusterService;
    private String lastGateError;
    private long lastLookup;
    private boolean codingLogged;
    private KombiMapGuard(IContext context, AndroidAutoClusterIntegration integration) {
        this.context = context; this.integration = integration;
    }
    public static void start(IContext context, AndroidAutoClusterIntegration integration) {
        try {
            if (!Mh2pAutoRoute.register(context, integration)) return;
            Thread t = new Thread(new KombiMapGuard(context, integration), "KombiMapGuard");
            t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); t.start();
        } catch (Throwable t) { safeLog(integration, "KOMBI_MAP: guard not started: " + t); }
    }
    public void run() {
        log(BUILD + " started");
        log("MH2P_VIDEO_GATE WAIT initialized=0 or first_drawn=0; AA transport unchanged");
        openTracker();
        while (true) {
            try { tick(); } catch (Throwable t) { log("tick error: " + t); }
            try { Thread.sleep(500L); } catch (InterruptedException e) { return; }
        }
    }
    private void tick() throws Exception {
        long now = System.currentTimeMillis();
        if (mapClusterService == null && now - lastLookup >= 5000L) { lastLookup = now; lookup(); }
        if (!codingLogged) logNavMapTransmissionMode();
        // Release first on disconnect; capture also establishes free-slot admission.
        Mh2pAutoRoute.poll(integration);
        try { Mh2pVideoGate.poll(integration, mapClusterService); }
        catch (Throwable failure) {
            String error = failure.toString();
            if (!error.equals(lastGateError)) { log("MH2P_VIDEO_GATE WAIT probe failed: " + error); lastGateError = error; }
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
