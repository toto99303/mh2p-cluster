/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Additive P2711 RemoteZoomView observer. No navigation/AA command is sent.
 * Attach/close must run on the HMI dispatcher, matching OEM listener lifetime.
 * OEM has no removeListener API: retire our weak delegate without editing its set.
 */
public final class Mh2pWheelBinding {
    public interface Consumer { void onSteps(int relativeSteps); }
    private final Consumer consumer;
    private WeakReference view;
    private Handler handler;

    public Mh2pWheelBinding(Consumer consumer) {
        if (consumer == null) throw new IllegalArgumentException("missing consumer");
        this.consumer = consumer;
    }
    public synchronized boolean attach(Object service) throws Exception {
        if (service == null) { close(); return false; }
        Class wrapper = Class.forName("de.audi.tghu.navi.app.map.osgi.wrapper.IMapClusterServiceWrapper");
        if (wrapper.isInstance(service)) {
            Class base = Class.forName("de.audi.tghu.navi.app.util.AbstractNaviServiceWrapper");
            Method get = base.getDeclaredMethod("get", new Class[0]); get.setAccessible(true);
            service = get.invoke(service, new Object[0]);
        }
        if (service == null) { close(); return false; }
        Class impl = Class.forName("de.audi.tghu.navi.app.cluster.MapClusterService");
        if (!impl.isInstance(service)) { close(); return false; }
        Object current = impl.getMethod("getRemoteZoomView", new Class[0]).invoke(service, new Object[0]);
        if (current == null) { close(); return false; }
        if (view != null && view.get() == current && handler != null && handler.active) return true;
        close();
        Class listener = Class.forName("de.audi.tghu.navi.app.map.zoom.IZoomView$IZoomViewListener");
        Handler next = new Handler(this);
        Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class[] {listener}, next);
        try {
            current.getClass().getMethod("addListener", new Class[] {listener}).invoke(current, new Object[] {proxy});
            view = new WeakReference(current); handler = next;
        } catch (Exception failure) { next.active = false; throw failure; }
        return true;
    }
    public synchronized void close() {
        if (handler != null) handler.active = false;
        handler = null; view = null;
    }
    private static final class Handler implements InvocationHandler {
        private final WeakReference target;
        volatile boolean active = true;
        Handler(Mh2pWheelBinding binding) { target = new WeakReference(binding); }
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("hashCode".equals(name)) return new Integer(System.identityHashCode(proxy));
            if ("equals".equals(name)) return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
            if ("toString".equals(name)) return "MH2p passive wheel observer";
            Mh2pWheelBinding binding = (Mh2pWheelBinding) target.get();
            if (active && binding != null && "onZoomLevelChanged".equals(name)
                    && args != null && args.length == 1 && args[0] instanceof Integer) {
                try { binding.consumer.onSteps(((Integer) args[0]).intValue()); }
                catch (Throwable ignored) { /* Never throw into OEM dispatch. */ }
            }
            return null;
        }
    }
}
