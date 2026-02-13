package com.datadog.profiling.utils.zstd;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates an unrolled match-copy implementation. Uses Unsafe getLong/putLong for 8-byte strides
 * with a byte-by-byte tail loop.
 */
final class MatchCopyGenerator implements Opcodes {
  private static final String PKG = "com/datadog/profiling/utils/zstd/";
  private static final String UNSAFE_UTILS = "datadog/trace/util/UnsafeUtils";

  private MatchCopyGenerator() {}

  static MatchCopy generate() {
    String className = PKG + "GeneratedMatchCopy";

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        V1_8,
        ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC,
        className,
        null,
        "java/lang/Object",
        new String[] {PKG + "MatchCopy"});

    // default constructor
    MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    init.visitCode();
    init.visitVarInsn(ALOAD, 0);
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    init.visitInsn(RETURN);
    init.visitMaxs(1, 1);
    init.visitEnd();

    // copy(byte[] dst, long dstOffset, byte[] src, long srcOffset, int length)
    // slots: this=0, dst=1, dstOff=2(long), src=4, srcOff=5(long), length=7
    // locals: remaining=8, d=9(long), s=11(long)
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "copy", "([BJ[BJI)V", null, null);
    mv.visitCode();

    mv.visitVarInsn(ILOAD, 7);
    mv.visitVarInsn(ISTORE, 8);
    mv.visitVarInsn(LLOAD, 2);
    mv.visitVarInsn(LSTORE, 9);
    mv.visitVarInsn(LLOAD, 5);
    mv.visitVarInsn(LSTORE, 11);

    Label longLoop = new Label();
    Label longEnd = new Label();
    Label byteLoop = new Label();
    Label byteEnd = new Label();

    // 8-byte stride loop
    mv.visitLabel(longLoop);
    mv.visitVarInsn(ILOAD, 8);
    mv.visitIntInsn(BIPUSH, 8);
    mv.visitJumpInsn(IF_ICMPLT, longEnd);

    // putLong(dst, d, getLong(src, s))
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(LLOAD, 9);
    mv.visitVarInsn(ALOAD, 4);
    mv.visitVarInsn(LLOAD, 11);
    mv.visitMethodInsn(INVOKESTATIC, UNSAFE_UTILS, "getLong", "(Ljava/lang/Object;J)J", false);
    mv.visitMethodInsn(INVOKESTATIC, UNSAFE_UTILS, "putLong", "(Ljava/lang/Object;JJ)V", false);

    mv.visitVarInsn(LLOAD, 9);
    mv.visitLdcInsn(8L);
    mv.visitInsn(LADD);
    mv.visitVarInsn(LSTORE, 9);

    mv.visitVarInsn(LLOAD, 11);
    mv.visitLdcInsn(8L);
    mv.visitInsn(LADD);
    mv.visitVarInsn(LSTORE, 11);

    mv.visitIincInsn(8, -8);
    mv.visitJumpInsn(GOTO, longLoop);
    mv.visitLabel(longEnd);

    // byte-by-byte tail
    mv.visitLabel(byteLoop);
    mv.visitVarInsn(ILOAD, 8);
    mv.visitJumpInsn(IFLE, byteEnd);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(LLOAD, 9);
    mv.visitVarInsn(ALOAD, 4);
    mv.visitVarInsn(LLOAD, 11);
    mv.visitMethodInsn(INVOKESTATIC, UNSAFE_UTILS, "getByte", "(Ljava/lang/Object;J)B", false);
    mv.visitMethodInsn(INVOKESTATIC, UNSAFE_UTILS, "putByte", "(Ljava/lang/Object;JB)V", false);

    mv.visitVarInsn(LLOAD, 9);
    mv.visitInsn(LCONST_1);
    mv.visitInsn(LADD);
    mv.visitVarInsn(LSTORE, 9);

    mv.visitVarInsn(LLOAD, 11);
    mv.visitInsn(LCONST_1);
    mv.visitInsn(LADD);
    mv.visitVarInsn(LSTORE, 11);

    mv.visitIincInsn(8, -1);
    mv.visitJumpInsn(GOTO, byteLoop);
    mv.visitLabel(byteEnd);

    mv.visitInsn(RETURN);
    mv.visitMaxs(6, 13);
    mv.visitEnd();

    cw.visitEnd();

    byte[] bytecode = cw.toByteArray();
    AsmClassLoader loader = new AsmClassLoader(MatchCopyGenerator.class.getClassLoader());
    Class<?> clazz = loader.defineClass(className.replace('/', '.'), bytecode);
    try {
      return (MatchCopy) clazz.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to instantiate generated MatchCopy", e);
    }
  }
}
