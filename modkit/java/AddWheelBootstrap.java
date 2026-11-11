/* Host build tool only. CC BY-NC-SA 4.0, matching this repository. */
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.*;

/** Add guarded wheel startup and a passive command observer, preserving original
 * method bodies. Operates on the already-published candidate5 classes only.
 */
public final class AddWheelBootstrap {
    private static final String OWNER="de/audi/app/terminalmode/smartphone/androidauto2/nav/zoom/Mh2pWheelZoom";
    private static final String GUARD="de/audi/app/terminalmode/smartphone/androidauto2/nav/KombiMapGuard";
    public static void main(String[] args) throws Exception {
        if (args.length!=3) throw new IllegalArgumentException("subsystem|integration input.class output.class");
        byte[] input=Files.readAllBytes(Path.of(args[1]));
        ClassReader reader=new ClassReader(input);
        ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        int[] matches={0};
        if (args[0].equals("subsystem")) {
            final int[] constructors={0};
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                    if (n.equals("<init>")) constructors[0]++;
                    return null;
                }
            },ClassReader.SKIP_CODE);
            if (constructors[0]!=1) throw new IllegalStateException("Expected one pinned constructor");
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                    MethodVisitor original=super.visitMethod(access,name,desc,signature,exceptions);
                    if (!name.equals("<init>")) return original;
                    return new MethodVisitor(Opcodes.ASM9,original) {
                        final Label begin=new Label(),end=new Label(),handler=new Label(),done=new Label();
                        @Override public void visitCode() {
                            super.visitCode();
                            // Register before the existing constructor handlers: the
                            // add-on's failure must not be stolen by an outer catch.
                            super.visitTryCatchBlock(begin,end,handler,"java/lang/Throwable");
                        }
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean iface) {
                            if (opcode==Opcodes.INVOKESTATIC && owner.equals(GUARD) && method.equals("start")) {
                                matches[0]++;
                                super.visitInsn(Opcodes.DUP2);
                                super.visitMethodInsn(opcode,owner,method,descriptor,iface);
                                super.visitLabel(begin);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,OWNER,"start","(Ljava/lang/Object;Ljava/lang/Object;)V",false);
                                super.visitLabel(end); super.visitJumpInsn(Opcodes.GOTO,done);
                                super.visitLabel(handler); super.visitInsn(Opcodes.POP);
                                super.visitLabel(done);
                            } else super.visitMethodInsn(opcode,owner,method,descriptor,iface);
                        }
                    };
                }
            },0);
        } else if (args[0].equals("integration")) {
            final String integration=reader.getClassName();
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                    if (name.equals("sendMirrorCommandUngated") && desc.equals("(Ljava/lang/String;)V")) {
                        matches[0]++;
                        return super.visitMethod(access,"sendMirrorCommandWithoutWheel",desc,signature,exceptions);
                    }
                    return super.visitMethod(access,name,desc,signature,exceptions);
                }
                @Override public void visitEnd() {
                    MethodVisitor m=super.visitMethod(Opcodes.ACC_PRIVATE,"sendMirrorCommandUngated","(Ljava/lang/String;)V",null,null);
                    m.visitCode();
                    emitCommand(m,false);
                    m.visitVarInsn(Opcodes.ALOAD,0); m.visitVarInsn(Opcodes.ALOAD,1);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL,integration,"sendMirrorCommandWithoutWheel","(Ljava/lang/String;)V",false);
                    emitCommand(m,true);
                    m.visitInsn(Opcodes.RETURN); m.visitMaxs(0,0); m.visitEnd();
                    super.visitEnd();
                }
            },0);
        } else throw new IllegalArgumentException("Unknown mode");
        if (matches[0]!=1) throw new IllegalStateException("Expected exactly one candidate5 instrumentation site, got "+matches[0]);
        Files.write(Path.of(args[2]),writer.toByteArray());
    }
    private static void emitCommand(MethodVisitor m,boolean after) {
        m.visitVarInsn(Opcodes.ALOAD,0); m.visitVarInsn(Opcodes.ALOAD,1);
        m.visitInsn(after?Opcodes.ICONST_1:Opcodes.ICONST_0);
        guarded(m,"command","(Ljava/lang/Object;Ljava/lang/String;Z)V");
    }
    private static void guarded(MethodVisitor m,String method,String descriptor) {
        Label begin=new Label(),end=new Label(),handler=new Label(),done=new Label();
        m.visitTryCatchBlock(begin,end,handler,"java/lang/Throwable");
        m.visitLabel(begin);
        m.visitMethodInsn(Opcodes.INVOKESTATIC,OWNER,method,descriptor,false);
        m.visitLabel(end); m.visitJumpInsn(Opcodes.GOTO,done);
        m.visitLabel(handler); m.visitInsn(Opcodes.POP);
        m.visitLabel(done);
    }
}
