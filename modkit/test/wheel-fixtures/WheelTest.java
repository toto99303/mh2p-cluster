import de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom.*;
import de.audi.tghu.navi.app.cluster.MapClusterService;
import de.audi.tghu.navi.app.map.osgi.wrapper.IMapClusterServiceWrapper;
import de.audi.tghu.navi.app.map.zoom.IZoomView;

public class WheelTest {
    static void check(boolean b, String name) {
        if (!b) throw new AssertionError(name); System.out.println("PASS " + name);
    }
    static class Consumer implements Mh2pWheelBinding.Consumer {
        int calls, last;
        boolean fail;
        public void onSteps(int steps) { calls++; last = steps; if (fail) throw new RuntimeException("fixture"); }
    }
    public static void main(String[] args) throws Exception {
        Mh2pWheelQueue q = new Mh2pWheelQueue();
        check(!q.offer(1,1000), "unowned wheel event discarded");
        q.presentation(1,true); check(q.offer(1,1000), "first relative detent is not lost as an absolute seed");
        Mh2pWheelQueue.Event e = q.poll(1000);
        check(e != null && e.steps==1 && q.isCurrent(e,1000), "first owned detent immediately available");
        q.offer(3,1010); check(q.poll(1100)==null, "fast callbacks paced");
        Mh2pWheelQueue.Event second=q.poll(1150);
        check(second.steps==1&&second.sequence>e.sequence, "paced events carry distinct ordered identities");
        q.offer(-2,1160); check(q.poll(1300)==null, "reverse input cancels outstanding target error");
        check(!q.offer(Integer.MIN_VALUE,1300)&&!q.offer(17,1300), "outlier steps rejected without integer overflow");
        q.offer(-1,1300); e=q.poll(1300); q.presentation(1,false); q.presentation(1,true);
        check(!q.isCurrent(e,1300)&&q.poll(1300)==null, "tab leave and return invalidate dequeued events too");
        q.offer(1,1300); e=q.poll(1300); q.presentation(2,true);
        check(!q.isCurrent(e,1300), "reconnect invalidates prior connection input");
        q.offer(16,1300); int count=0;
        for(int i=0;i<8;i++) if(q.poll(1300+i*150)!=null) count++;
        check(count<=8 && q.poll(2600)==null, "bounded pending error and expiry prevent a long catch-up burst");
        q.presentation(3,true);
        for(int i=0;i<200;i++) { long t=3000+i*200; q.offer(1,t); checkSilent(q.poll(t)!=null); }
        check(true,"completed motion rebases instead of imposing a cumulative zoom ceiling");
        q.offer(1,50000); e=q.poll(50000);
        check(!q.isCurrent(e,49999)&&!q.offer(1,50000), "clock rollback cancels ownership and pending input");
        q.presentation(4,true); q.offer(1,50001); e=q.poll(50001); q.cancel();
        check(!q.isCurrent(e,50001), "explicit shutdown cancellation invalidates an in-flight intent");

        Consumer c=new Consumer(); Mh2pWheelBinding binding=new Mh2pWheelBinding(c);
        MapClusterService stock=new MapClusterService(); final int[] nativeCalls={0};
        stock.view.addListener(new IZoomView.IZoomViewListener() {
            public void onZoomLevelChanged(int s) { nativeCalls[0]++; }
            public void onGestureStarted() { } public void onGestureStopped() { } public void onZoomEvent() { }
        });
        check(binding.attach(new IMapClusterServiceWrapper(stock)), "unwrap verified P2711 map service and add observer");
        stock.view.fire(-3); check(c.calls==1&&c.last==-3&&nativeCalls[0]==1, "signed OEM steps and native listener both preserved");
        binding.attach(stock); stock.view.fire(-3);
        check(c.calls==2&&stock.view.listeners.size()==2, "duplicate registration does not duplicate wheel events");
        c.fail=true; stock.view.fire(1); check(nativeCalls[0]==3, "observer failure cannot escape into OEM callback"); c.fail=false;
        MapClusterService replacement=new MapClusterService(); binding.attach(replacement);
        int previous=c.calls; stock.view.fire(1); check(c.calls==previous,"retired service events ignored");
        replacement.view.fire(2); check(c.last==2,"replacement service events observed");
        binding.close(); previous=c.calls; replacement.view.fire(1); check(c.calls==previous,"closed observer inert without editing OEM listener set");
        check(!binding.attach(new IMapClusterServiceWrapper(null))&&!binding.attach(new Object()), "expired or unsupported services refused");
    }
    static void checkSilent(boolean b) { if(!b) throw new AssertionError("unbounded completed history"); }
}
