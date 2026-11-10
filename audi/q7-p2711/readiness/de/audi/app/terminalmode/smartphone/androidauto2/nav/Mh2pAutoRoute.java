/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav;

import de.audi.app.terminalmode.IContext;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** JVM-local ownership bridge. Callbacks only change intent; guard does I/O. */
public final class Mh2pAutoRoute {
    private static Object owner;
    private static Mh2pRouteEngine engine;
    private static boolean disabled;

    public static synchronized boolean register(IContext context, Object integration) {
        if (owner != null) {
            if (owner != integration) {
                disabled = true; engine.cancel();
                log(integration, "MH2P_AUTO_ROUTE MULTIPLE_INTEGRATIONS secondary video disabled until MMI restart");
            }
            return false;
        }
        owner = integration;
        final Backend backend = new Backend(context, integration);
        engine = new Mh2pRouteEngine(backend);
        try {
            Runtime.getRuntime().addShutdownHook(new Thread("Mh2pRouteShutdown") {
                public void run() { engine.shutdown(); backend.dmdt.close(); }
            });
        } catch (Throwable e) { log(integration, "MH2P_AUTO_ROUTE shutdown hook unavailable: " + e); }
        return true;
    }
    public static synchronized boolean active(Object integration) {
        return owner == integration && !disabled && engine != null;
    }
    public static synchronized boolean canPrepare(Object integration) {
        return active(integration) && engine.canPrepare();
    }
    public static synchronized void preparePending(Object integration) {
        if (active(integration)) engine.preparePending();
    }
    public static synchronized void command(Object integration, String command) {
        if (active(integration)) engine.command(command);
    }
    public static void poll(Object integration) {
        Mh2pRouteEngine e;
        synchronized (Mh2pAutoRoute.class) { e = owner == integration ? engine : null; }
        if (e != null) e.poll(System.currentTimeMillis());
    }
    static void log(Object integration, String message) {
        try { integration.getClass().getMethod("logCluster", new Class[] {String.class})
            .invoke(integration, new Object[] {message}); } catch (Throwable ignored) { }
    }

    private static final class Backend implements Mh2pRouteEngine.Backend {
        final IContext context;
        final Object integration;
        final Mh2pDmdt dmdt = new Mh2pDmdt();
        Backend(IContext context, Object integration) { this.context = context; this.integration = integration; }
        public Mh2pRoutePolicy.State capture() throws Exception {
            String gs = dmdt.query("gs"), gd = dmdt.query("gd");
            Mh2pRoutePolicy.State s = Mh2pRoutePolicy.parse(gs, gd, oem());
            File mailbox = new File("/tmp/cluster_ctl");
            s.aaSource = s.aaSource && mailbox.exists() && mailbox.length() == 0;
            return s;
        }
        public void select(int[] layers, int[] expectedOem) throws Exception {
            // No I/O under the HMI monitor. Recheck policy just before submission.
            if (!Mh2pRoutePolicy.equal(expectedOem, oem())) throw new IllegalStateException("HMI policy changed");
            dmdt.select(layers);
        }
        public void release() throws Exception { dmdt.release(); }
        public void log(String message) { Mh2pAutoRoute.log(integration, message); }
        private int[] oem() throws Exception {
            Object dm = context.getFramework().getHMIService().getDisplayManager();
            Class base = Class.forName("de.eso.hmiservice.display.DisplayManager");
            if (!base.isInstance(dm)) throw new IllegalStateException("unsupported display manager");
            synchronized (dm) {
                Method mapping = base.getDeclaredMethod("getInternalDisplayID", new Class[] {Integer.TYPE});
                mapping.setAccessible(true);
                int id = ((Integer) mapping.invoke(dm, new Object[] {new Integer(1)})).intValue();
                int mode = ((Integer) dm.getClass().getMethod("getDisplayMappingTestMode", new Class[0])
                    .invoke(dm, new Object[0])).intValue();
                if (id != 4 || mode != 0) throw new IllegalStateException("unsupported cockpit mapping");
                Field f = base.getDeclaredField("requestedContexts"); f.setAccessible(true);
                Object[] contexts = (Object[]) f.get(null);
                if (contexts == null || contexts.length <= 1 || contexts[1] == null) return null;
                Object dc = contexts[1];
                return Mh2pRoutePolicy.copy((int[]) dc.getClass().getMethod("getDisplayableList", new Class[0])
                    .invoke(dc, new Object[0]));
            }
        }
    }
}
