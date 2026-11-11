/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;

/** Worker-thread-only loopback link. Never invoke from the OEM wheel callback.
 * One socket and one in-flight request,100ms receive deadline. Input has no
 * primary-screen fallback when the companion is absent or unbound.
 */
public final class Mh2pWheelClient {
    public static final int HELLO=1, STATUS=2, LEASE=3, STEP=4;
    private final int client=(new Random().nextInt() & 0x7fffffff) | 1;
    private volatile DatagramSocket socket;
    private int request;

    public static final class Status {
        public final int cookie, epoch, flags, inputChannel, videoChannel;
        public final long generation, accepted, submitted;
        Status(byte[] b) {
            cookie=get(b,16); epoch=get(b,20); generation=unsigned(get(b,24));
            accepted=unsigned(get(b,28)); flags=get(b,32);
            inputChannel=b[40]&255; videoChannel=b[41]&255;
            submitted=unsigned(get(b,44));
        }
        public boolean ready() { return (flags&31)==31 && inputChannel>0 && inputChannel<255; }
    }

    public Status exchange(int op, int cookie, int epoch, long generation,
                           long sequence, int value, long age) throws IOException {
        if (op<HELLO || op>STEP || generation<0 || generation>0xffffffffL
                || sequence<0 || sequence>0xffffffffL || age<0 || age>1000)
            throw new IllegalArgumentException("invalid wheel intent");
        if (op==STEP && (sequence==0 || (value!=1 && value!=-1) || age>=1000))
            throw new IllegalArgumentException("invalid wheel step");
        DatagramSocket current=socket;
        if (current==null) {
            InetAddress local=InetAddress.getByName("127.0.0.1");
            current=new DatagramSocket(0,local);
            try {
                current.connect(local,46335);
                current.setSoTimeout(100);
                socket=current;
            } catch (IOException failure) { current.close(); throw failure; }
              catch (RuntimeException failure) { current.close(); throw failure; }
        }
        byte[] b=new byte[40];
        b[0]='M'; b[1]='Z'; b[2]='0'; b[3]='1'; b[4]=(byte)op; b[7]=40;
        int id=++request;
        put(b,8,id); put(b,12,client); put(b,16,cookie); put(b,20,epoch);
        put(b,24,(int)generation); put(b,28,(int)sequence); put(b,32,value); put(b,36,(int)age);
        current.send(new DatagramPacket(b,b.length));
        byte[] response=new byte[80];
        DatagramPacket packet=new DatagramPacket(response,response.length);
        current.receive(packet);
        if (packet.getLength()!=48 || response[0]!='M' || response[1]!='Z'
                || response[2]!='0' || response[3]!='1' || (response[4]&255)!=128
                || response[5]!=0 || response[6]!=0 || response[7]!=48
                || get(response,8)!=id || get(response,12)!=client)
            throw new IOException("wheel companion reply did not match this client/request");
        return new Status(response);
    }
    public void close() {
        DatagramSocket current=socket; socket=null;
        if (current!=null) current.close();
    }
    private static long unsigned(int n) { return ((long)n)&0xffffffffL; }
    private static int get(byte[] b,int i) {
        return (b[i]&255)<<24 | (b[i+1]&255)<<16 | (b[i+2]&255)<<8 | b[i+3]&255;
    }
    private static void put(byte[] b,int i,int n) {
        b[i]=(byte)(n>>>24); b[i+1]=(byte)(n>>>16); b[i+2]=(byte)(n>>>8); b[i+3]=(byte)n;
    }
}
