import de.audi.app.terminalmode.smartphone.androidauto2.nav.Mh2pDmdt;
import java.io.*;
import java.lang.reflect.*;

public class DmdtTest {
    static void check(boolean b,String name) { if(!b) throw new AssertionError(name); System.out.println("PASS "+name); }
    static class Alive extends Process {
        public int exitValue() { throw new IllegalThreadStateException("alive"); }
        public void destroy() { }
        public int waitFor() { return -1; }
        public InputStream getInputStream() { return null; }
        public InputStream getErrorStream() { return null; }
        public OutputStream getOutputStream() { return null; }
    }
    static String run(Mh2pDmdt d,String command,boolean query) throws Exception {
        Method m=Mh2pDmdt.class.getDeclaredMethod("run",new Class[]{String[].class,Boolean.TYPE}); m.setAccessible(true);
        try { return (String)m.invoke(d,new Object[]{new String[]{"/bin/sh","-c",command},new Boolean(query)}); }
        catch(InvocationTargetException e) { throw (Exception)e.getTargetException(); }
    }
    public static void main(String[] args) throws Exception {
        Mh2pDmdt d=new Mh2pDmdt();
        check("query-output".equals(run(d,"printf query-output",true)),"bounded host process captures stdout");
        try { run(d,"printf failure >&2; exit 7",false); throw new AssertionError("nonzero accepted"); }
        catch(IOException expected) { check(expected.toString().indexOf("status=7")>=0,"nonzero child fails with status"); }
        long start=System.currentTimeMillis();
        try { run(d,"exec sleep 10",true); throw new AssertionError("timeout missing"); }
        catch(IOException expected) { check(System.currentTimeMillis()-start<4000,"hung owned child times out within bound"); }
        Field child=Mh2pDmdt.class.getDeclaredField("queryChild"); child.setAccessible(true); child.set(d,new Alive());
        try { run(d,"exit 0",true); throw new AssertionError("overlapping query accepted"); }
        catch(IOException expected) { check(expected.toString().indexOf("previous owned child")>=0,"unreaped query blocks new query"); }
        check("release-lane".equals(run(d,"printf release-lane",false)),"unreaped read child does not block control lane");
        child=Mh2pDmdt.class.getDeclaredField("controlChild"); child.setAccessible(true); child.set(d,new Alive());
        try { run(d,"exit 0",false); throw new AssertionError("overlapping control accepted"); }
        catch(IOException expected) { check(true,"unreaped control blocks racing mutations"); }
        if(args.length==1) {
            ByteArrayOutputStream buf=new ByteArrayOutputStream(); InputStream in=new FileInputStream(args[0]);
            try { byte[] bytes=new byte[4096]; int n; while((n=in.read(bytes))!=-1) buf.write(bytes,0,n); } finally { in.close(); }
            byte[] original=buf.toByteArray(), patched=Mh2pDmdt.patchQuery(original);
            int changes=0;
            for(int i=0;i<original.length;i++) if(original[i]!=patched[i]) {
                changes++; if(!((i>=6895&&i<6905)||(i>=11788&&i<11790))) throw new AssertionError("unintended ELF byte change");
            }
            check(changes>0&&patched.length==original.length,"runtime query patch changes only verified sites without executing ARM input");
            original[100]^=1;
            try { Mh2pDmdt.patchQuery(original); throw new AssertionError("hash mismatch accepted"); }
            catch(IOException expected) { check(true,"runtime helper refuses modified stock input"); }
        }
    }
}
