/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** P2711 bootstrap. One worker/socket, bounded input and coalesced dispatcher jobs.
 * OEM classes are referenced reflectively; no stock class is included here.
 */
public final class Mh2pWheelZoom implements Runnable, Mh2pWheelBinding.Consumer {
    private static final Object START_LOCK=new Object();
    private static volatile Mh2pWheelZoom instance;
    private final Object context, integration, wake=new Object();
    private final Mh2pWheelQueue queue=new Mh2pWheelQueue();
    private final Mh2pWheelBinding binding=new Mh2pWheelBinding(this);
    private final Mh2pWheelClient client=new Mh2pWheelClient();
    private volatile boolean running=true, attached, bindingPending, probeOwned;
    private volatile long probeAt;
    private volatile int renderIntent=-1;
    private volatile Object service, factory;
    private volatile String callbackError;
    private volatile Object clusterDispatcher;
    private Object bundle, mapTracker, factoryTracker, attemptedFactory, boundService;
    private String lastLog;

    private Mh2pWheelZoom(Object context,Object integration) { this.context=context; this.integration=integration; }
    public static void start(Object context,Object integration) {
        Mh2pWheelZoom next;
        synchronized (START_LOCK) {
            if (instance!=null) {
                if (instance.integration!=integration) instance.running=false;
                return; // Never accumulate workers for duplicate subsystem instances.
            }
            next=new Mh2pWheelZoom(context,integration); instance=next;
        }
        try {
            final Mh2pWheelZoom owner=next;
            Runtime.getRuntime().addShutdownHook(new Thread("Mh2pWheelShutdown") {
                public void run() { owner.stop(); }
            });
            Thread worker=new Thread(next,"Mh2pWheelWorker");
            worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
        } catch (Throwable failure) { next.running=false; next.log("disabled: "+failure); }
    }
    private void stop() {
        running=false; queue.cancel(); binding.close(); client.close(); signal();
    }
    /** Passive notification around the original candidate1 FIFO sender. Negative
     * intent cancels before its I/O; positive intent is published after return.
     * Never take a monitor that the sender/guard needs and never perform I/O.
     */
    public static void command(Object integration,String command,boolean after) {
        Mh2pWheelZoom owner=instance;
        if (owner==null || owner.integration!=integration || !owner.running) return;
        if (!after && ("pause".equals(command) || "stop".equals(command)
                || command.startsWith("prepare"))) {
            owner.renderIntent=0; owner.queue.cancel(); owner.signal();
        } else if (after && ("resume".equals(command) || command.startsWith("start"))) {
            owner.renderIntent="resume".equals(command) || command.indexOf("capture=h264")>=0?1:0;
            if (owner.renderIntent==0) owner.queue.cancel();
            owner.signal();
        }
    }
    public void onSteps(int relativeSteps) {
        // Called on the native CLUSTER dispatcher. No files, sockets or logs.
        queue.offer(relativeSteps,System.currentTimeMillis()); signal();
    }
    private void signal() { synchronized (wake) { wake.notifyAll(); } }

    public void run() {
        int cookie=0,epoch=0;
        long connection=0;
        boolean hello=true;
        Mh2pWheelQueue.Event held=null;
        try {
            bundle=call(call(context,"getFramework"),"getBundleCxt");
            mapTracker=track("de.audi.tghu.navi.app.cluster.IMapClusterService",false);
            factoryTracker=track("de.audi.tghu.navi.app.dispatcher.NaviDispatcherFactory",true);
            log("started; candidate5 source33; secondary rotary only");
            while (running) {
                try {
                    updateBinding();
                    Mh2pWheelClient.Status status=client.exchange(hello?1:2,cookie,epoch,0,0,0,0);
                    hello=false;
                    if (cookie!=status.cookie || epoch!=status.epoch) {
                        cookie=status.cookie; epoch=status.epoch; held=null;
                        if (++connection<=0) throw new IllegalStateException("connection identity exhausted");
                    }
                    long now=System.currentTimeMillis();
                    if (now-probeAt>=400 || held!=null || queue.hasPending()) probe();
                    now=System.currentTimeMillis();
                    boolean owns=status.ready() && attached && probeOwned && now>=probeAt && now-probeAt<=1000;
                    queue.presentation(connection,owns);
                    if (held!=null && !queue.isCurrent(held,now)) held=null;
                    if (held==null && owns) held=queue.poll(now);
                    long generation=queue.generation();
                    boolean fresh=owns && held!=null && probeAt>=held.capturedAt && now-probeAt<=250;
                    client.exchange(3,cookie,epoch,generation,0,fresh?1:0,0);
                    if (fresh && queue.isCurrent(held,System.currentTimeMillis())) {
                        now=System.currentTimeMillis();
                        client.exchange(4,cookie,epoch,held.generation,held.sequence,held.steps,now-held.capturedAt);
                        held=null;
                    }
                    if (callbackError!=null) log(callbackError);
                    else log("state epoch="+(epoch&0xffffffffL)+" nativeFlags="+status.flags
                             +" attached="+attached+" confirmedMap="+probeOwned);
                    pause(held!=null || queue.hasPending()?40:400);
                } catch (Throwable failure) {
                    queue.cancel(); held=null; hello=true; client.close();
                    log("waiting: "+cause(failure)); pause(500);
                }
            }
        } catch (Throwable failure) { log("disabled: "+cause(failure)); }
        finally {
            running=false; queue.cancel(); client.close(); binding.close();
            closeTracker(mapTracker); closeTracker(factoryTracker);
            if (clusterDispatcher!=null) try { call(clusterDispatcher,"dispose"); } catch (Throwable ignored) { }
        }
    }
    private void pause(long ms) {
        synchronized (wake) { if (running) try { wake.wait(ms); } catch (InterruptedException e) { running=false; } }
    }
    private Object track(String name,final boolean isFactory) throws Exception {
        Class custom=type("org.osgi.util.tracker.ServiceTrackerCustomizer");
        Object observer=Proxy.newProxyInstance(custom.getClassLoader(),new Class[]{custom},new InvocationHandler() {
            public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
                String name=method.getName();
                if ("hashCode".equals(name)) return new Integer(System.identityHashCode(proxy));
                if ("equals".equals(name)) return Boolean.valueOf(args!=null && args.length==1 && proxy==args[0]);
                if ("toString".equals(name)) return "MH2p wheel service observer";
                if ("addingService".equals(name)) {
                    Object found=call(bundle,"getService",new Class[]{type("org.osgi.framework.ServiceReference")},new Object[]{args[0]});
                    if (isFactory) factory=found; else service=found;
                    signal(); return found;
                }
                if ("removedService".equals(name)) {
                    if (isFactory && factory==args[1]) { factory=null; attached=false; queue.cancel(); binding.close(); }
                    if (!isFactory && service==args[1]) { service=null; attached=false; queue.cancel(); binding.close(); }
                    call(bundle,"ungetService",new Class[]{type("org.osgi.framework.ServiceReference")},new Object[]{args[0]});
                    signal();
                }
                return null;
            }
        });
        Object tracker=type("org.osgi.util.tracker.ServiceTracker").getConstructor(
            new Class[]{type("org.osgi.framework.BundleContext"),type("java.lang.String"),custom})
            .newInstance(new Object[]{bundle,name,observer});
        call(tracker,"open"); return tracker;
    }
    private void closeTracker(Object tracker) {
        if (tracker!=null) try { call(tracker,"close"); } catch (Throwable ignored) { }
    }
    private void updateBinding() throws Exception {
        final Object current=service;
        Object maker=factory;
        if (maker!=attemptedFactory) {
            attached=false; queue.cancel(); binding.close(); boundService=null;
            if (clusterDispatcher!=null) try { call(clusterDispatcher,"dispose"); } catch (Throwable ignored) { }
            clusterDispatcher=null; attemptedFactory=null;
            // Wait until both native services are registered before creating a wrapper.
            if (maker==null || current==null) return;
            Object channel=call(call(context,"getFramework"),"getLogChannel",
                new Class[]{type("java.lang.String")},new Object[]{"MH2pWheelZoom"});
            Class kind=type("de.audi.tghu.navi.app.dispatcher.ENaviDispatcher");
            clusterDispatcher=call(maker,"createDispatcher",
                new Class[]{kind,type("java.lang.String"),type("de.audi.atip.log.LogChannel")},
                new Object[]{kind.getField("CLUSTER").get(null),"MH2pWheelZoom",channel});
            attemptedFactory=maker;
        }
        if (clusterDispatcher==null || bindingPending || boundService==current) return;
        bindingPending=true;
        final Object requestedFactory=maker, requestedDispatcher=clusterDispatcher;
        try {
            call(clusterDispatcher,"execute",new Class[]{type("java.lang.Runnable")},new Object[]{new Runnable() {
                public void run() {
                    try {
                        if (running && service==current && factory==requestedFactory
                                && clusterDispatcher==requestedDispatcher) {
                            attached=binding.attach(current);
                            boundService=current; callbackError=null;
                        }
                    } catch (Throwable failure) { attached=false; callbackError="binding: "+cause(failure); }
                    finally { bindingPending=false; signal(); }
                }
            }});
        } catch (Exception failure) { bindingPending=false; throw failure; }
    }
    private void probe() throws Exception {
        // Worker only: never make an HMI dispatcher wait for the display manager's
        // monitor. A stalled stock monitor can stop this one worker, while its
        // native presentation lease expires and OEM callbacks remain unblocked.
        probeOwned=false;
        probeOwned=confirmedSource33();
        probeAt=System.currentTimeMillis();
    }
    private boolean confirmedSource33() throws Exception {
        if (!running || !attached || !Boolean.TRUE.equals(type(
                "de.audi.app.terminalmode.smartphone.androidauto2.nav.Mh2pVideoGate")
                .getMethod("isReady",new Class[0]).invoke(null,new Object[0]))) return false;
        int intent=renderIntent;
        if (intent<0) {
            Field stage=integration.getClass().getDeclaredField("clusterState"); stage.setAccessible(true);
            intent=stage.getInt(integration)==2?1:0; // Startup-only fallback before the first observed command.
        }
        if (intent!=1) return false;
        Object dm=call(call(call(context,"getFramework"),"getHMIService"),"getDisplayManager");
        Class base=type("de.eso.hmiservice.display.DisplayManager");
        if (!base.isInstance(dm)) return false;
        synchronized (dm) {
            Field answered=base.getDeclaredField("lastRequestAnswered"); answered.setAccessible(true);
            Field confirmed=base.getDeclaredField("confirmedActiveContext"); confirmed.setAccessible(true);
            Field requested=base.getDeclaredField("requestedContexts"); requested.setAccessible(true);
            boolean[] done=(boolean[])answered.get(dm);
            Object[] active=(Object[])confirmed.get(null), wanted=(Object[])requested.get(null);
            if (done==null || done.length<=1 || !done[1] || active==null || active.length<=1
                    || wanted==null || wanted.length<=1 || active[1]==null || wanted[1]==null) return false;
            int[] a=(int[])call(active[1],"getDisplayableList"), b=(int[])call(wanted[1],"getDisplayableList");
            if (a==null || b==null || a.length!=b.length || a.length>64) return false;
            boolean map=false;
            for (int i=0;i<a.length;i++) { if (a[i]!=b[i]) return false; if (a[i]==33) map=true; }
            return map;
        }
    }
    private void log(String message) {
        if (message.equals(lastLog)) return;
        lastLog=message;
        try { call(integration,"logCluster",new Class[]{type("java.lang.String")},new Object[]{"MH2P_WHEEL "+message}); }
        catch (Throwable ignored) { }
    }
    private static String cause(Throwable failure) {
        Throwable cause=failure.getCause(); return (cause==null?failure:cause).toString();
    }
    private static Class type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static Object call(Object target,String name) throws Exception { return call(target,name,new Class[0],new Object[0]); }
    private static Object call(Object target,String name,Class[] signature,Object[] args) throws Exception {
        if (target==null) throw new IllegalStateException("missing service for "+name);
        Method method=target.getClass().getMethod(name,signature); method.setAccessible(true);
        return method.invoke(target,args);
    }
}
