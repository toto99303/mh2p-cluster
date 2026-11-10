/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.WeakHashMap;

/** Secondary-video-only gate. Intent callbacks never wait for native tools.
 * Prepare warms an invisible decoder once source62 admission is established.
 * Resume waits for actual stock map readiness and mailbox consumption.
 */
public final class Mh2pVideoGate {
    private static final WeakHashMap gates = new WeakHashMap();
    private static volatile boolean ready;
    private String prepare, start, control;
    private boolean prepared;
    private static synchronized Mh2pVideoGate gate(Object integration) {
        Mh2pVideoGate g = (Mh2pVideoGate) gates.get(integration);
        if (g == null) { g = new Mh2pVideoGate(); gates.put(integration, g); }
        return g;
    }
    public static boolean isReady() { return ready; }
    public static void send(Object integration, String command) {
        Mh2pVideoGate g = gate(integration);
        synchronized (g) {
            if (command.startsWith("prepare")) {
                g.prepare = command; g.start = null;
                Mh2pAutoRoute.preparePending(integration);
            } else if (command.startsWith("start")) {
                g.prepare = null; g.start = command;
                Mh2pAutoRoute.preparePending(integration);
            } else if ("resume".equals(command)) {
                if (!"stop".equals(g.control)) g.control = null;
                g.start = command;
            } else if ("pause".equals(command) || "stop".equals(command)) {
                g.start = null;
                if ("stop".equals(command)) g.prepare = null;
                if (g.prepared) g.control = command;
                Mh2pAutoRoute.command(integration, command); // Cancel selection immediately.
            }
        }
    }
    public static void poll(Object integration, Object service) throws Exception {
        if (!ready && stockMapReady(service)) {
            ready = true;
            Mh2pAutoRoute.log(integration, "MH2P_VIDEO_GATE READY initialized=1 first_drawn=1");
        }
        if (!Mh2pAutoRoute.active(integration)) return;
        Mh2pVideoGate g = gate(integration);
        synchronized (g) { g.flush(integration); }
    }
    static boolean stockMapReady(Object service) throws Exception {
        if (service == null) return false;
        Class wrapper = Class.forName("de.audi.tghu.navi.app.map.osgi.wrapper.IMapClusterServiceWrapper");
        if (wrapper.isInstance(service)) {
            Class base = Class.forName("de.audi.tghu.navi.app.util.AbstractNaviServiceWrapper");
            Method get = base.getDeclaredMethod("get", new Class[0]);
            get.setAccessible(true);
            service = get.invoke(service, new Object[0]);
        }
        if (service == null) return false;
        Class impl = Class.forName("de.audi.tghu.navi.app.cluster.MapClusterService");
        if (!impl.isInstance(service)) return false;
        Field mapField = impl.getDeclaredField("kombiMap");
        mapField.setAccessible(true);
        Object map = mapField.get(service);
        if (map == null) return false;
        Class abstractMap = Class.forName("de.audi.tghu.navi.app.map.AbstractMap");
        Field drawn = abstractMap.getDeclaredField("mapDrawn");
        if (!Modifier.isVolatile(drawn.getModifiers())) return false;
        drawn.setAccessible(true);
        // isInitialized() was verified to be a pure mMapInitialized field read.
        return drawn.getBoolean(map) && Boolean.TRUE.equals(
            abstractMap.getMethod("isInitialized", new Class[0]).invoke(map, new Object[0]));
    }

    private void flush(Object integration) {
        // The native mailbox is a read/truncate file, not a command queue.
        File mailbox = new File("/tmp/cluster_ctl");
        if (!mailbox.exists() || mailbox.length() != 0) return;
        if (control != null) {
            String c = control;
            if (raw(integration, c)) {
                control = null;
                if ("stop".equals(c)) prepared = false;
            }
            return;
        }
        if (prepare != null) {
            if (!Mh2pAutoRoute.canPrepare(integration)) return;
            if (prepare.indexOf("capture=h264") < 0 && !ready) return;
            if (raw(integration, prepare)) { prepare = null; prepared = true; }
            return; // Never overwrite prepare with resume in the same poll.
        }
        if (ready && start != null) {
            if ("resume".equals(start) && !prepared) return;
            if (!"resume".equals(start) && !Mh2pAutoRoute.canPrepare(integration)) return;
            if (raw(integration, start)) { start = null; prepared = true; }
        }
    }
    private static boolean raw(Object integration, String command) {
        try {
            Method m = integration.getClass().getDeclaredMethod("sendMirrorCommandUngated", new Class[] {String.class});
            m.setAccessible(true); m.invoke(integration, new Object[] {command});
            Mh2pAutoRoute.command(integration, command);
            return true;
        } catch (Throwable failure) {
            Mh2pAutoRoute.log(integration, "MH2P_VIDEO_GATE command failed: " + failure); return false;
        }
    }
}
