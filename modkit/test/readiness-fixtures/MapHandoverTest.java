import de.audi.app.terminalmode.smartphone.androidauto2.nav.Mh2pMapHandover;
import de.audi.tghu.navi.app.map.AbstractMap;
import de.audi.tghu.navi.app.cluster.MapClusterService;
import de.audi.tghu.navi.app.map.osgi.wrapper.IMapClusterServiceWrapper;
import de.audi.tghu.navi.app.map.dsi.MVRequestControl;

public class MapHandoverTest {
    public static class Integration {
        public StringBuffer logs = new StringBuffer();
        public void logCluster(String message) { logs.append(message).append('\n'); }
    }
    public static class Core {
        public MVRequestControl active;
        public Object getMVRequestControlActive() { return active; }
    }
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    public static void main(String[] args) {
        Integration integration = new Integration();
        Mh2pMapHandover handover = new Mh2pMapHandover(integration);
        AbstractMap map = new AbstractMap();
        Core core = new Core();
        core.active = new MVRequestControl(9); // deliberately not hardcoded instance 3
        map.setCore(core);
        Object service = new IMapClusterServiceWrapper(new MapClusterService(map));
        check(handover.hide(service, true), "stock wrapper accepts hide request");
        check(core.active.calls.toString().equals("suspend;hidden;"), "stock request handles suspend before visibility");
        check(core.active.response.state().equals("SUSPENDING"), "suspension acknowledgement is expected by stock state");
        core.active.response.ackSuspend();
        check(core.active.response.state().equals("INACTIVE"), "fixture accepts expected suspension acknowledgement");
        check(handover.hide(service, false), "periodic hide accepted");
        check(core.active.calls.toString().equals("suspend;hidden;hidden;"), "periodic hide does not repeat suspension");
        check(handover.restore(service), "restore uses stock wrapper");
        check(core.active.calls.toString().endsWith("wake;visible;unfreeze;"), "restore requests wake, visibility and unfreeze in order");
        check(core.active.response.state().equals("WAKING_UP"), "wake request is not falsely reported as ACTIVE");
        check(integration.logs.toString().indexOf("instance=9 renderer=WAKING_UP") >= 0, "diagnostic reports actual selected instance and pending state");
        core.active.response.ackWake();
        handover.observe(service);
        check(integration.logs.toString().indexOf("restore-observed instance=9 renderer=ACTIVE visible=true frozen=false") >= 0, "delayed diagnostic observes acknowledged renderer state");
        check(handover.restore(service), "bounded retry still wakes renderer");
        check(core.active.calls.toString().endsWith("wake;visible;unfreeze;wake;visible;unfreeze;"), "retry does not silently omit wakeup");
        MVRequestControl prior = core.active;
        core.active = new MVRequestControl(12);
        String before = prior.calls.toString();
        check(handover.restore(service) && prior.calls.toString().equals(before), "renderer replacement selects current stock instance");
        core.active.ready = false;
        before = core.active.calls.toString();
        check(!handover.restore(service) && core.active.calls.toString().equals(before), "unready renderer receives no requests");
        core.active.ready = true;
        core.active.missingDiagnosticResponse = true;
        check(handover.restore(service), "missing diagnostics do not suppress valid restore");
        check(!handover.restore(null), "missing map service fails without raw DSI fallback");
        check(!handover.restore(new IMapClusterServiceWrapper(null)), "expired wrapper fails safely");
        map.setCore(null);
        check(!handover.restore(service), "missing map core fails safely");
        check(!handover.restore(new Object()), "unsupported service fails safely");
    }
}
