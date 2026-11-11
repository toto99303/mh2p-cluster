import de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom.Mh2pAaWheelWire;
public class WheelWireDump {
    public static void main(String[] args) {
        byte[] bytes=Mh2pAaWheelWire.inputReport(Long.parseLong(args[0]),Integer.parseInt(args[1]));
        StringBuffer b=new StringBuffer();
        for(int i=0;i<bytes.length;i++) { int value=bytes[i]&255; if(value<16)b.append('0'); b.append(Integer.toHexString(value)); }
        System.out.println(b.toString());
    }
}
