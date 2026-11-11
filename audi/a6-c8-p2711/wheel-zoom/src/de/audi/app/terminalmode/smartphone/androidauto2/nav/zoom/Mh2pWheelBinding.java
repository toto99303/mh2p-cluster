/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;

/** Additive P2711 RemoteZoomView observer. No navigation/AA command is sent.
 * Attach must run on the OEM CLUSTER dispatcher, matching its listener lifetime.
 * Close only retires our memory delegate and may run from any thread.
 * OEM has no removeListener API: retire our weak delegate without editing its set.
 */
public final class Mh2pWheelBinding {
    // A view has no removeListener API. Reuse its one proxy across rebinds;
    // neither the proxy nor its weak target holds the weak-key view alive.
    private static final WeakHashMap observers = new WeakHashMap();
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
        synchronized (observers) {
            Handler next = (Handler) observers.get(current);
            if (next != null) {
                Object previous = next.target.get();
                if (next.active && previous != null && previous != this)
                    throw new IllegalStateException("wheel view already has an active observer");
                next.target = new WeakReference(this);
                next.active = true;
            } else {
                next = new Handler(this);
                Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class[] {listener}, next);
                try {
                    current.getClass().getMethod("addListener", new Class[] {listener}).invoke(current, new Object[] {proxy});
                    observers.put(current, next);
                } catch (Exception failure) { next.active = false; throw failure; }
            }
            view = new WeakReference(current); handler = next;
        }
        return true;
    }
    public synchronized void close() {
        synchronized (observers) {
            if (handler != null && handler.target.get() == this) handler.active = false;
        }
        handler = null; view = null;
    }
    private static final class Handler implements InvocationHandler {
        private volatile WeakReference target;
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
