import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.objectweb.asm.*;

/** Host investigation only: run the exact shipped owned classes with a fake
 * completed Process whose output never reaches EOF until the fixture releases it.
 * No QNX executable, OEM class, actual subprocess or vehicle API is executed.
 */
public final class DmdtPipeRetention {
    private static final List<Blocked> streams = new ArrayList<Blocked>();
    private static int patchedCalls;
    public static Process fakeExec(Runtime ignored, String[] command) {
        final Blocked stdout = new Blocked(), stderr = new Blocked();
        streams.add(stdout); streams.add(stderr);
        return new Process() {
            public int exitValue() { return 0; }
            public int waitFor() { return 0; }
            public void destroy() { stdout.close(); stderr.close(); }
            public InputStream getInputStream() { return stdout; }
            public InputStream getErrorStream() { return stderr; }
            public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        };
    }
    private static final class Blocked extends InputStream {
        private final CountDownLatch released = new CountDownLatch(1);
        public int read() throws IOException {
            try { released.await(); return -1; }
            catch (InterruptedException e) { throw new IOException("fixture interrupted"); }
        }
        public void close() { released.countDown(); }
    }
    private static List<Thread> readers() {
        List<Thread> found = new ArrayList<Thread>();
        for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.isAlive() && "Mh2pRoutePipe".equals(t.getName())) found.add(t);
        return found;
    }
    public static void main(String[] args) throws Exception {
        final Path directory = Paths.get(args[0]);
        ClassLoader loader = new ClassLoader(DmdtPipeRetention.class.getClassLoader()) {
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                try {
                    byte[] code = Files.readAllBytes(directory.resolve(name.replace('.', '/')+".class"));
                    if (name.endsWith(".Mh2pDmdt")) {
                        ClassReader reader = new ClassReader(code);
                        ClassWriter writer = new ClassWriter(reader, 0);
                        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(a,n,d,s,e)) {
                                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                                        if (op==Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Runtime")
                                                && name.equals("exec") && desc.equals("([Ljava/lang/String;)Ljava/lang/Process;")) {
                                            patchedCalls++;
                                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"DmdtPipeRetention","fakeExec",
                                                "(Ljava/lang/Runtime;[Ljava/lang/String;)Ljava/lang/Process;",false);
                                        } else super.visitMethodInsn(op,owner,name,desc,itf);
                                    }
                                };
                            }
                        }, 0);
                        code = writer.toByteArray();
                    }
                    return defineClass(name, code, 0, code.length);
                } catch (IOException failure) { throw new ClassNotFoundException(name,failure); }
            }
        };
        if (!readers().isEmpty()) throw new AssertionError("unexpected pre-existing readers");
        try {
            Class<?> type = loader.loadClass("de.audi.app.terminalmode.smartphone.androidauto2.nav.Mh2pDmdt");
            Object runner = type.getConstructor().newInstance();
            Method run = type.getDeclaredMethod("run",String[].class,boolean.class); run.setAccessible(true);
            for (int attempt=1; attempt<=3; attempt++) {
                try {
                    run.invoke(runner,new Object[] {new String[] {"fixture-only-no-process"},Boolean.TRUE});
                    throw new AssertionError("blocked reader accepted as complete");
                } catch (InvocationTargetException failure) {
                    if (!failure.getTargetException().toString().contains("query pipe did not close")) throw failure;
                }
                int count = readers().size();
                if (count != attempt*2) throw new AssertionError("expected retained readers: "+count);
                System.out.println("REPRODUCED completed_queries="+attempt+" retained_reader_threads="+count);
            }
            if (patchedCalls!=1) throw new AssertionError("unexpected process-call instrumentation");
            System.out.println("REPRODUCED: next query admitted after child exit while prior readers remain blocked");
        } finally {
            List<Thread> cleanup = readers();
            for (Blocked stream : streams) stream.close();
            for (Thread thread : cleanup) thread.join(2000L);
            if (!readers().isEmpty()) throw new AssertionError("fixture leaked reader threads");
            System.out.println("PASS fixture cleanup: all simulated blocked readers released");
        }
    }
}
