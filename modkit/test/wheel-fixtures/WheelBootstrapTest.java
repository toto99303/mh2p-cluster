import java.lang.reflect.*;
import de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom.*;
import de.audi.app.terminalmode.smartphone.androidauto2.nav.Mh2pVideoGate;
import de.audi.tghu.navi.app.cluster.MapClusterService;
import de.audi.tghu.navi.app.dispatcher.ENaviDispatcher;
import de.audi.atip.log.LogChannel;
import de.eso.hmiservice.display.DisplayManager;

/** Exercises owned coordinator's actual reflection paths against synthetic APIs. */
public class WheelBootstrapTest {
    static Class owner;
    public static class Context { public Framework getFramework(){return framework;} }
    public static class Framework {
        public Hmi getHMIService(){return hmi;}
        public LogChannel getLogChannel(String name){return new LogChannel();}
    }
    public static class Hmi { public DisplayManager getDisplayManager(){return dm;} }
    public static class Integration { private int clusterState=2; public void logCluster(String s){} }
    public static class Dispatcher {
        Runnable pending; int disposed,jobs;
        public void execute(Runnable job) {
            if(pending!=null)throw new AssertionError("unbounded dispatcher jobs"); pending=job;jobs++;
        }
        public void dispose(){disposed++;}
        void drain(){Runnable job=pending;pending=null;if(job!=null)job.run();}
    }
    public static class Factory {
        int calls; Dispatcher latest;
        public Dispatcher createDispatcher(ENaviDispatcher kind,String name,LogChannel log) {
            if(kind!=ENaviDispatcher.CLUSTER)throw new AssertionError("wrong native dispatcher");
            calls++;return latest=new Dispatcher();
        }
    }
    static Framework framework=new Framework(); static Hmi hmi=new Hmi();
    static DisplayManager dm=new DisplayManager();
    static void set(Object object,String name,Object value)throws Exception {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    static Object get(Object object,String name)throws Exception {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    static Object invoke(Object object,String name)throws Exception {
        Method m=owner.getDeclaredMethod(name,new Class[0]);m.setAccessible(true);return m.invoke(object,new Object[0]);
    }
    static void check(boolean b,String text){if(!b)throw new AssertionError(text);System.out.println("PASS "+text);}
    public static void main(String[] args)throws Exception {
        owner=Class.forName("de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom.Mh2pWheelZoom");
        Constructor ctor=owner.getDeclaredConstructor(new Class[]{Object.class,Object.class});ctor.setAccessible(true);
        Integration integration=new Integration();Object zoom=ctor.newInstance(new Object[]{new Context(),integration});
        set(null,"instance",zoom);
        Factory factory=new Factory();MapClusterService map=new MapClusterService();
        set(zoom,"factory",factory);set(zoom,"service",map);
        invoke(zoom,"updateBinding");invoke(zoom,"updateBinding");
        check(factory.calls==1&&factory.latest.jobs==1&&map.view.listeners.size()==0,
            "registration waits for CLUSTER dispatcher; one pending job");
        factory.latest.drain();invoke(zoom,"updateBinding");
        check(map.view.listeners.size()==1&&Boolean.TRUE.equals(get(zoom,"attached")),"coordinator binds verified raw view once");
        dm.fixture(false,new int[]{41,33},new int[]{41,33});
        check(Boolean.FALSE.equals(invoke(zoom,"confirmedSource33")),"requested map without confirmation cannot authorize input");
        dm.fixture(true,new int[]{41,33},new int[]{41,61});
        check(Boolean.FALSE.equals(invoke(zoom,"confirmedSource33")),"pending context change cancels presentation ownership");
        dm.fixture(true,new int[]{41,33},new int[]{41,33});
        check(Boolean.TRUE.equals(invoke(zoom,"confirmedSource33")),"ready gate, rendering and matching confirmed33 authorize input");
        Mh2pVideoGate.ready=false;
        check(Boolean.FALSE.equals(invoke(zoom,"confirmedSource33")),"unready baseline gate blocks zoom");Mh2pVideoGate.ready=true;
        Mh2pWheelQueue queue=(Mh2pWheelQueue)get(zoom,"queue");queue.presentation(1,true);map.view.fire(1);
        check(queue.hasPending(),"registered callback reaches bounded queue");
        Mh2pWheelZoom.command(integration,"pause",false);
        check(!queue.hasPending()&&Boolean.FALSE.equals(invoke(zoom,"confirmedSource33")),"pause cancels queued input before original I/O");
        Mh2pWheelZoom.command(integration,"resume",true);
        check(Boolean.TRUE.equals(invoke(zoom,"confirmedSource33")),"completed resume restores render intent");
        Dispatcher old=factory.latest;set(zoom,"factory",null);invoke(zoom,"updateBinding");
        set(zoom,"factory",factory);invoke(zoom,"updateBinding");factory.latest.drain();
        check(old.disposed==1&&factory.calls==2&&map.view.listeners.size()==1,"factory re-registration recreates wrapper without listener growth");
        invoke(zoom,"stop");check(!queue.hasPending(),"shutdown cancels memory queue without waiting for native transport");
    }
}
