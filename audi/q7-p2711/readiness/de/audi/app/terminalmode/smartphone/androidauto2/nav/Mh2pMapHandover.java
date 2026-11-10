/* Licensed under CC BY-NC-SA 4.0, matching this MH2p repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** P2711 native-map requests through the stock MVRequestControl wrapper.
 * Direct DSI calls bypass SUSPENDING/WAKING_UP state changes and can leave
 * the stock response handler treating the acknowledgements as unexpected.
 * No stock fields are written and no display-context numbers are assumed.
 */
public final class Mh2pMapHandover {
    private final Object integration;

    public Mh2pMapHandover(Object integration) { this.integration = integration; }

    public boolean hide(Object service, boolean suspend) {
        try {
            Object request = request(service);
            logState(request, "before-hide");
            if (suspend) invoke(request, "suspendMapViewer");
            bool(request, "viewSetVisible", false);
            logState(request, "hide-requested");
            return true;
        } catch (Throwable failure) {
            log("hide failed: " + failure);
            return false;
        }
    }

    /** Call after stock context/size restoration so visibility is applied last.
     * Repeat wake through the wrapper on each bounded guard restore attempt;
     * issuing a request is not treated as acknowledgement or visible pixels.
     */
    public boolean restore(Object service) {
        try {
            Object request = request(service);
            logState(request, "before-restore");
            invoke(request, "wakeupMapViewer");
            bool(request, "viewSetVisible", true);
            bool(request, "viewFreeze", false);
            logState(request, "restore-requested");
            return true;
        } catch (Throwable failure) {
            log("restore failed: " + failure);
            return false;
        }
    }

    public void observe(Object service) {
        try { logState(request(service), "restore-observed"); }
        catch (Throwable failure) { log("observe failed: " + failure); }
    }

    private Object request(Object service) throws Exception {
        if (service == null) throw new IllegalStateException("map service unavailable");
        Class wrapper = Class.forName("de.audi.tghu.navi.app.map.osgi.wrapper.IMapClusterServiceWrapper");
        if (wrapper.isInstance(service)) {
            Class base = Class.forName("de.audi.tghu.navi.app.util.AbstractNaviServiceWrapper");
            Method get = base.getDeclaredMethod("get", new Class[0]);
            get.setAccessible(true);
            service = get.invoke(service, new Object[0]);
        }
        if (service == null) throw new IllegalStateException("map service expired");
        Class impl = Class.forName("de.audi.tghu.navi.app.cluster.MapClusterService");
        if (!impl.isInstance(service)) throw new IllegalStateException("unsupported map service");
        Field f = impl.getDeclaredField("kombiMap");
        f.setAccessible(true);
        Object map = f.get(service);
        if (map == null) throw new IllegalStateException("cockpit map unavailable");
        Object core = invoke(map, "getMVRequest");
        if (core == null) throw new IllegalStateException("map core unavailable");
        Object request = invoke(core, "getMVRequestControlActive");
        Class expected = Class.forName("de.audi.tghu.navi.app.map.dsi.MVRequestControl");
        if (request == null || !expected.isInstance(request))
            throw new IllegalStateException("active stock request unavailable");
        if (!Boolean.TRUE.equals(invoke(request, "isReady")))
            throw new IllegalStateException("active stock request not ready");
        return request;
    }

    private void logState(Object request, String phase) {
        try {
            Object response = invoke(request, "getMVResponseControl");
            if (response == null) throw new IllegalStateException("response unavailable");
            Class type = Class.forName("de.audi.tghu.navi.app.map.dsi.MVResponseControl");
            Field state = type.getDeclaredField("currentState");
            state.setAccessible(true);
            log(phase + " instance=" + invoke(request, "getInstanceID")
                + " renderer=" + state.get(response)
                + " visible=" + invoke(response, "isViewVisible")
                + " frozen=" + invoke(response, "isViewFreeze")
                + " runLevel=" + invoke(response, "getMapViewerRunLevel"));
        } catch (Throwable failure) {
            // Diagnostics must not suppress a valid wake/hide request.
            log(phase + " state unavailable: " + failure);
        }
    }

    private static Object invoke(Object target, String name) throws Exception {
        return target.getClass().getMethod(name, new Class[0]).invoke(target, new Object[0]);
    }

    private static void bool(Object target, String name, boolean value) throws Exception {
        target.getClass().getMethod(name, new Class[] { Boolean.TYPE })
            .invoke(target, new Object[] { value ? Boolean.TRUE : Boolean.FALSE });
    }

    private void log(String message) {
        try {
            integration.getClass().getMethod("logCluster", new Class[] { Class.forName("java.lang.String") })
                .invoke(integration, new Object[] { "MH2P_HAND_BACK " + message });
        } catch (Throwable ignored) { }
    }
}
