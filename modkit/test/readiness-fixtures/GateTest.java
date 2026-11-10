import de.audi.app.terminalmode.smartphone.androidauto2.nav.*;
import de.audi.tghu.navi.app.map.AbstractMap;
import de.audi.tghu.navi.app.cluster.MapClusterService;
import de.audi.tghu.navi.app.map.osgi.wrapper.IMapClusterServiceWrapper;
import java.io.*;

public class GateTest {
    public static class Integration {
        StringBuffer calls=new StringBuffer();
        private void sendMirrorCommandUngated(String c) throws Exception {
            calls.append(c).append(';'); FileOutputStream out=new FileOutputStream("/tmp/cluster_ctl"); out.write(1); out.close();
        }
    }
    static void check(boolean b,String name) { if(!b) throw new AssertionError(name); System.out.println("PASS "+name); }
    static void consume() throws Exception { new FileOutputStream("/tmp/cluster_ctl").close(); }
    public static void main(String[] args) throws Exception {
        File mailbox=new File("/tmp/cluster_ctl");
        if(!mailbox.createNewFile()) throw new IOException("Test refuses existing host mailbox");
        try {
            Integration i=new Integration(); AbstractMap map=new AbstractMap();
            Object service=new IMapClusterServiceWrapper(new MapClusterService(map));
            Mh2pVideoGate.send(i,"prepare capture=h264 mode=fill"); Mh2pVideoGate.send(i,"resume");
            check(i.calls.length()==0,"callback queues intent without I/O");
            Mh2pVideoGate.poll(i,service); check(i.calls.length()==0,"collision admission holds decoder prepare");
            Mh2pAutoRoute.admitted=true; Mh2pVideoGate.poll(i,service);
            check(i.calls.toString().equals("prepare capture=h264 mode=fill;"),"invisible decoder warms before map readiness");
            consume(); map.set(true,false); Mh2pVideoGate.poll(i,service); check(!Mh2pVideoGate.isReady(),"initialization alone holds resume");
            map.set(false,true); Mh2pVideoGate.poll(i,service); check(!Mh2pVideoGate.isReady(),"first draw alone holds resume");
            // Occupied mailbox prevents resume even when map becomes ready.
            FileOutputStream occupied=new FileOutputStream(mailbox); occupied.write(1); occupied.close(); map.set(true,true); Mh2pVideoGate.poll(i,service);
            check(Mh2pVideoGate.isReady()&&i.calls.toString().indexOf("resume;")<0,"ready map still waits for consumed prepare");
            consume(); Mh2pVideoGate.poll(i,service); check(i.calls.toString().endsWith("resume;"),"resume after both signals and mailbox consumption");
            Mh2pVideoGate.send(i,"pause"); check("pause".equals(Mh2pAutoRoute.last),"pause cancels route immediately without mailbox wait");
            consume(); Mh2pVideoGate.poll(i,service); check(i.calls.toString().endsWith("pause;"),"pause reaches owned decoder");
            Mh2pVideoGate.send(i,"resume"); Mh2pVideoGate.send(i,"stop"); consume(); Mh2pVideoGate.poll(i,service);
            check(i.calls.toString().endsWith("pause;stop;"),"disconnect discards queued resume");
            consume(); Mh2pVideoGate.send(i,"prepare capture=h264 mode=letter"); Mh2pVideoGate.send(i,"resume"); Mh2pVideoGate.poll(i,service);
            check(i.calls.toString().endsWith("prepare capture=h264 mode=letter;"),"fresh prepare never overwritten by same-poll resume");
            Mh2pVideoGate.poll(i,service); check(i.calls.toString().endsWith("prepare capture=h264 mode=letter;"),"busy mailbox holds reconnect resume");
            consume(); Mh2pVideoGate.poll(i,service); check(i.calls.toString().endsWith("mode=letter;resume;"),"reconnect resumes latest settings");
        } finally { mailbox.delete(); }
    }
}
