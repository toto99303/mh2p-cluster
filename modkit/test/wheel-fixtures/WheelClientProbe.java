import de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom.Mh2pWheelClient;

/** Host loopback fixture; no HMI/bootstrap is instantiated. */
public class WheelClientProbe {
    public static void main(String[] args) throws Exception {
        Mh2pWheelClient link=new Mh2pWheelClient();
        try {
            Mh2pWheelClient.Status s=link.exchange(1,0,0,0,0,0,0);
            if (!s.ready() || s.inputChannel!=9 || s.videoChannel!=19)
                throw new AssertionError("unexpected secondary input mapping");
            link.exchange(3,s.cookie,s.epoch,1,0,1,0);
            s=link.exchange(4,s.cookie,s.epoch,1,1,1,0);
            if (s.accepted!=1) throw new AssertionError("native worker did not accept Java intent");
            System.out.println("PASS Java1.4 client and native companion use the same session/intent protocol");
        } finally { link.close(); }
    }
}
