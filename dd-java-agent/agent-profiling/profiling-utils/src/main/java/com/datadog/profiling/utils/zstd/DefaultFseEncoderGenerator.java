package com.datadog.profiling.utils.zstd;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates a specialized FseEncoder wrapper around a pre-built FseCompressionTable. The wrapper
 * creates a monomorphic call site for the JIT, enabling aggressive inlining of the table's
 * begin/encode/finish methods.
 */
final class DefaultFseEncoderGenerator implements Opcodes {
  private static final String PKG = "com/datadog/profiling/utils/zstd/";
  private static final String BIT_OUTPUT_STREAM = PKG + "BitOutputStream";
  private static final String FSE_ENCODER = PKG + "FseEncoder";
  private static final String FSE_TABLE = PKG + "FseCompressionTable";
  private static final String TABLE_DESC = "L" + FSE_TABLE + ";";

  private DefaultFseEncoderGenerator() {}

  static FseEncoder generate(FseCompressionTable table, String name) {
    String className = PKG + "GeneratedFseEncoder$" + name;

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(
        V1_8,
        ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC,
        className,
        null,
        "java/lang/Object",
        new String[] {FSE_ENCODER});

    // field for table reference
    cw.visitField(ACC_PRIVATE | ACC_FINAL, "table", TABLE_DESC, null, null).visitEnd();

    // constructor(FseCompressionTable table)
    MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + TABLE_DESC + ")V", null, null);
    init.visitCode();
    init.visitVarInsn(ALOAD, 0);
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    init.visitVarInsn(ALOAD, 0);
    init.visitVarInsn(ALOAD, 1);
    init.visitFieldInsn(PUTFIELD, className, "table", TABLE_DESC);
    init.visitInsn(RETURN);
    init.visitMaxs(2, 2);
    init.visitEnd();

    // begin(int symbol) -> int
    MethodVisitor beginMv = cw.visitMethod(ACC_PUBLIC, "begin", "(I)I", null, null);
    beginMv.visitCode();
    beginMv.visitVarInsn(ALOAD, 0);
    beginMv.visitFieldInsn(GETFIELD, className, "table", TABLE_DESC);
    beginMv.visitVarInsn(ILOAD, 1);
    beginMv.visitInsn(I2B);
    beginMv.visitMethodInsn(INVOKEVIRTUAL, FSE_TABLE, "begin", "(B)I", false);
    beginMv.visitInsn(IRETURN);
    beginMv.visitMaxs(2, 2);
    beginMv.visitEnd();

    // encode(BitOutputStream out, int state, int symbol) -> int
    String bos = "L" + BIT_OUTPUT_STREAM + ";";
    MethodVisitor encodeMv = cw.visitMethod(ACC_PUBLIC, "encode", "(" + bos + "II)I", null, null);
    encodeMv.visitCode();
    encodeMv.visitVarInsn(ALOAD, 0);
    encodeMv.visitFieldInsn(GETFIELD, className, "table", TABLE_DESC);
    encodeMv.visitVarInsn(ALOAD, 1);
    encodeMv.visitVarInsn(ILOAD, 2);
    encodeMv.visitVarInsn(ILOAD, 3);
    encodeMv.visitMethodInsn(INVOKEVIRTUAL, FSE_TABLE, "encode", "(" + bos + "II)I", false);
    encodeMv.visitInsn(IRETURN);
    encodeMv.visitMaxs(4, 4);
    encodeMv.visitEnd();

    // finish(BitOutputStream out, int state) -> void
    MethodVisitor finishMv = cw.visitMethod(ACC_PUBLIC, "finish", "(" + bos + "I)V", null, null);
    finishMv.visitCode();
    finishMv.visitVarInsn(ALOAD, 0);
    finishMv.visitFieldInsn(GETFIELD, className, "table", TABLE_DESC);
    finishMv.visitVarInsn(ALOAD, 1);
    finishMv.visitVarInsn(ILOAD, 2);
    finishMv.visitMethodInsn(INVOKEVIRTUAL, FSE_TABLE, "finish", "(" + bos + "I)V", false);
    finishMv.visitInsn(RETURN);
    finishMv.visitMaxs(3, 3);
    finishMv.visitEnd();

    cw.visitEnd();

    byte[] bytecode = cw.toByteArray();
    AsmClassLoader loader = new AsmClassLoader(DefaultFseEncoderGenerator.class.getClassLoader());
    Class<?> clazz = loader.defineClass(className.replace('/', '.'), bytecode);
    try {
      return (FseEncoder)
          clazz.getDeclaredConstructor(FseCompressionTable.class).newInstance(table);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to instantiate generated FseEncoder", e);
    }
  }
}
