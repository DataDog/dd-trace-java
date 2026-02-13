package com.datadog.profiling.utils.zstd;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Shared ASM helper methods for encoder generators. */
final class EncoderGenerator implements Opcodes {
  private EncoderGenerator() {}

  /** Emit the most efficient constant load instruction for the given int value. */
  static void emitIntConst(MethodVisitor mv, int value) {
    if (value >= -1 && value <= 5) {
      mv.visitInsn(ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      mv.visitIntInsn(SIPUSH, value);
    } else {
      mv.visitLdcInsn(value);
    }
  }

  /** Emit the most efficient constant load instruction for the given long value. */
  static void emitLongConst(MethodVisitor mv, long value) {
    if (value == 0L) {
      mv.visitInsn(LCONST_0);
    } else if (value == 1L) {
      mv.visitInsn(LCONST_1);
    } else {
      mv.visitLdcInsn(value);
    }
  }
}
