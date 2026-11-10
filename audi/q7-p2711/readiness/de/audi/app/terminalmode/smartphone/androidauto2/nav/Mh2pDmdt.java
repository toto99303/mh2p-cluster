/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Bounded child processes. Only query commands use the RAM-only flush copy. */
public final class Mh2pDmdt {
    private static final String STOCK = "/eso/bin/apps/dmdt";
    private static final String SHA = "300aa12be6e94e6017f4cbdd5b5b96996f166875c7cbd9ec008a3089adf0e0f2";
    private File helper;
    private Process queryChild, controlChild;

    public static byte[] patchQuery(byte[] original) throws Exception {
        if (original.length != 44680) throw new IOException("unsupported dmdt length");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(original);
        StringBuffer hash = new StringBuffer();
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 255; if (b < 16) hash.append('0'); hash.append(Integer.toHexString(b));
        }
        if (!SHA.equals(hash.toString())) throw new IOException("unsupported stock dmdt SHA256");
        byte[] out = new byte[original.length]; System.arraycopy(original, 0, out, 0, out.length);
        byte[] name = new byte[] {'f','f','l','u','s','h',0,0,0,0};
        System.arraycopy(name, 0, out, 6895, name.length);
        out[11788] = 0; out[11789] = 32; // movs r0,#0 before the reused fflush import.
        return out;
    }

    private void ensureHelper() throws Exception {
        if (helper != null) return;
        byte[] source = read(new FileInputStream(STOCK), 44680);
        byte[] patched = patchQuery(source);
        File file = File.createTempFile("mh2p-route62-query-", ".bin", new File("/tmp"));
        try {
            FileOutputStream out = new FileOutputStream(file);
            try { out.write(patched); } finally { out.close(); }
            run(new String[] {"/bin/ksh", "-c", "PATH=/bin:/usr/bin:/mnt/app/armle/usr/bin:/mnt/app/armle/bin; export PATH; exec chmod 700 \"$1\"", "mh2p-query", file.getAbsolutePath()}, false);
            file.deleteOnExit(); helper = file;
        } finally { if (helper == null) file.delete(); }
    }

    public String query(String mode) throws Exception {
        if (!"gs".equals(mode) && !"gd".equals(mode)) throw new IOException("query command refused");
        ensureHelper();
        return run(new String[] {helper.getAbsolutePath(), mode}, true);
    }
    public void select(int[] layers) throws Exception {
        String[] args = new String[layers.length+3];
        args[0] = STOCK; args[1] = "sd"; args[2] = "4";
        for (int i = 0; i < layers.length; i++) args[i+3] = Integer.toString(layers[i]);
        run(args, false);
    }
    public void release() throws Exception { run(new String[] {STOCK,"sb","4"}, false); }
    public void close() { if (helper != null) helper.delete(); }

    private String run(String[] command, boolean query) throws Exception {
        Process previous = query ? queryChild : controlChild;
        if (previous != null) {
            try { previous.exitValue(); }
            catch (IllegalThreadStateException alive) { throw new IOException("previous owned child has not exited"); }
        }
        Process p = Runtime.getRuntime().exec(command);
        if (query) queryChild = p; else controlChild = p;
        p.getOutputStream().close();
        Pump stdout = new Pump(p.getInputStream()), stderr = new Pump(p.getErrorStream());
        stdout.start(); stderr.start();
        boolean exited = false; int status = -1;
        for (int elapsed = 0; elapsed < 2000; elapsed += 20) {
            try { status = p.exitValue(); exited = true; break; }
            catch (IllegalThreadStateException alive) { Thread.sleep(20L); }
        }
        if (!exited) {
            p.destroy();
            for (int elapsed = 0; elapsed < 400; elapsed += 20) {
                try { p.exitValue(); exited = true; break; }
                catch (IllegalThreadStateException alive) { Thread.sleep(20L); }
            }
            // Do not synchronously close an InputStream another thread may be
            // reading: its monitor could block cleanup. Keep at most one owned
            // child per lane; pumps are daemon threads with bounded buffers.
            throw new IOException("dmdt child timed out; exit confirmed=" + exited);
        }
        stdout.join(300L); stderr.join(300L);
        if (stdout.isAlive() || stderr.isAlive()) {
            throw new IOException("query pipe did not close");
        }
        if (status != 0 || stdout.failure != null || stderr.failure != null)
            throw new IOException("dmdt failed status=" + status + " stderr=" + stderr.text());
        return stdout.text();
    }

    static byte[] read(InputStream in, int limit) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[2048]; int n;
            while ((n = in.read(b)) != -1) {
                if (out.size()+n > limit) throw new IOException("output limit exceeded");
                out.write(b, 0, n);
            }
            return out.toByteArray();
        } finally { in.close(); }
    }
    private static final class Pump extends Thread {
        private final InputStream in;
        private byte[] bytes = new byte[0];
        volatile IOException failure;
        Pump(InputStream in) { super("Mh2pRoutePipe"); this.in = in; setDaemon(true); }
        public void run() {
            try { bytes = read(in, 65536); } catch (IOException e) { failure = e; }
        }
        String text() throws IOException { return new String(bytes, "UTF-8"); }
    }
}
