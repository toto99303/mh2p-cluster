import de.audi.app.terminalmode.smartphone.androidauto2.nav.*;
import java.util.ArrayList;

/** Fake backend only: never loads or executes Audi/QNX classes. */
public class RouteTest {
    static void check(boolean value,String name) { if(!value) throw new AssertionError(name); System.out.println("PASS "+name); }
    static class Fake implements Mh2pRouteEngine.Backend {
        Mh2pRoutePolicy.State s=new Mh2pRoutePolicy.State();
        Mh2pRouteEngine e=new Mh2pRouteEngine(this);
        int selections, releases;
        boolean failQuery, failSelect, failRelease, stopInQuery, stopInSelect, acceptRelease=true, acceptSelect=true;
        ArrayList messages=new ArrayList();
        Fake() { s.actual=new int[]{41,33}; s.oem=new int[]{41,33}; s.clusterGeometry=true; s.nativeSource=true; }
        public Mh2pRoutePolicy.State capture() throws Exception {
            if(stopInQuery) { stopInQuery=false; e.command("stop"); }
            if(failQuery) throw new Exception("query failure"); return s;
        }
        public void select(int[] layers,int[] expected) throws Exception {
            selections++;
            if(acceptSelect) s.actual=Mh2pRoutePolicy.copy(layers);
            if(stopInSelect) e.command("stop");
            if(failSelect) throw new Exception("possibly partial selection");
        }
        public void release() throws Exception {
            releases++;
            if(failRelease) throw new Exception("release failed");
            if(acceptRelease) s.actual=Mh2pRoutePolicy.copy(s.oem);
        }
        public void log(String text) { messages.add(text); }
        void prepare() { e.poll(1000); check(e.canPrepare(),"free-slot admission"); e.command("prepare capture=h264"); s.aaRegistered=s.aaSource=true; e.command("resume"); }
    }
    public static void main(String[] args) throws Exception {
        String gs="displaymanager reports the following system information:\n display: Main Display\n size: 1540 x 720\n16 (HMI)\n display: Cluster Display\n terminal: LVDS2\n size: 1920 x 722\n displayables:\n41 (3D)\n33 (MAP)\n display: HUD\n60 (HUD)\n";
        String gd="displaymanager knows 3 displayables:\n33 Software 1920 x 600 MAP\n41 Software 1920 x 722 3D\n62 Software 1920 x 722 AA\n";
        Mh2pRoutePolicy.State parsed=Mh2pRoutePolicy.parse(gs,gd,new int[]{41,33});
        check(parsed.clusterGeometry&&parsed.nativeSource&&parsed.aaSource&&Mh2pRoutePolicy.equal(parsed.actual,new int[]{41,33}),"parse actual P2711 query shape");
        check(!Mh2pRoutePolicy.parse(gs,gd.replaceAll("62 Software 1920 x 722","62 Software 800 x 480"),null).aaSource,"wrong AA dimensions refused");
        parsed=Mh2pRoutePolicy.parse(gs,gd.replaceAll("62 Software","62 Image"),null);
        check(parsed.aaRegistered&&!parsed.aaSource,"non-software source62 still blocks slot admission");
        try { Mh2pRoutePolicy.parse(gs,gd.replaceAll("knows 3","knows 4"),null); throw new AssertionError("truncated query accepted"); }
        catch(IllegalArgumentException expected) { check(true,"incomplete source report refused"); }
        check(Mh2pRoutePolicy.equal(Mh2pRoutePolicy.aaLayers(new int[]{33,20,41}),new int[]{62,20,41}),"preserve ordered KDK layers");
        check(Mh2pRoutePolicy.aaLayers(new int[]{41,33,33})==null&&Mh2pRoutePolicy.aaLayers(new int[]{41,19})==null,"duplicate and unsupported policies refused");
        try { Mh2pRoutePolicy.parse("",gd,null); throw new AssertionError("missing display accepted"); } catch(IllegalArgumentException expected) { }
        Fake f=new Fake(); f.s.aaRegistered=true; f.e.poll(1000); check(!f.e.canPrepare(),"foreign source62 blocks prepare");
        f=new Fake(); f.prepare(); f.e.poll(1100); f.e.poll(1200);
        check(f.selections==1&&f.e.isOwned()&&f.messages.toString().indexOf("AA_CONFIRMED")>=0,"selection requires registered source and observed layers");
        f.e.command("pause"); f.e.poll(1300); check(f.releases==1&&!f.e.isOwned(),"pause releases and confirms latest OEM layout");
        f.e.command("resume"); f.e.poll(1400); check(f.selections==2,"paused decoder resumes without restart heuristic");
        f.s.oem=new int[]{33,20,41}; f.e.poll(1500); check(Mh2pRoutePolicy.equal(f.s.actual,new int[]{62,20,41}),"live supported policy change preserves order");
        f.s.oem=new int[]{41,19}; f.e.poll(1600); f.e.poll(1700); check(!f.e.isOwned()&&Mh2pRoutePolicy.equal(f.s.actual,f.s.oem),"unsupported policy releases to current OEM");
        f=new Fake(); f.prepare(); f.stopInQuery=true; f.e.poll(1100); check(f.selections==0,"stop during query cancels stale selection");
        f=new Fake(); f.prepare(); f.stopInSelect=true; f.e.poll(1100); check(f.releases==1,"stop during native select immediately releases late result");
        f=new Fake(); f.prepare(); f.failSelect=true; f.e.poll(1100); check(f.releases==1,"partial failed select is cleaned up");
        f=new Fake(); f.prepare(); f.e.poll(1100); f.failQuery=true; f.e.command("stop"); f.e.poll(1200); check(f.releases==1,"disconnect releases before failed read query");
        f=new Fake(); f.prepare(); f.acceptSelect=false; f.e.poll(1100); f.e.poll(3200); check(f.releases==1&&f.messages.toString().indexOf("SELECT_UNCONFIRMED")>=0,"exit success without observed selection falls back");
        f=new Fake(); f.prepare(); f.e.poll(1100); f.acceptRelease=false; f.e.command("stop"); f.e.poll(1200); f.e.poll(2800); f.e.poll(4400); f.e.poll(6000);
        check(f.releases==3&&f.e.isOwned()&&!f.e.canPrepare(),"unconfirmed release blocks fresh route and bounds retries");
        f=new Fake(); f.prepare(); f.e.poll(1100); f.e.command("stop"); f.e.preparePending(); f.e.poll(1200);
        check(!f.e.canPrepare(),"fast reconnect waits for old source retirement"); f.s.aaRegistered=f.s.aaSource=false; f.e.poll(1300); check(f.e.canPrepare(),"new prepare admitted after old source disappears");
        f=new Fake(); f.prepare(); f.s.actual=new int[]{41,61}; f.e.poll(1100); check(f.selections==0,"pre-existing foreign composition left alone");
        f=new Fake(); f.prepare(); f.e.poll(1100); f.e.poll(900); check(f.releases==1,"clock rollback fails to OEM");
        f=new Fake(); f.prepare(); f.e.poll(1100); f.e.shutdown(); check(f.releases==1,"ordinary shutdown attempts release");
    }
}
