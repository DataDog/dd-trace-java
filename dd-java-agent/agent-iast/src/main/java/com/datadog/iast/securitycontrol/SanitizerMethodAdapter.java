package com.datadog.iast.securitycontrol;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class SanitizerMethodAdapter extends AbstractMethodAdapter {

  public SanitizerMethodAdapter(
      final MethodVisitor mv,
      final SecurityControl securityControl,
      final int accessFlags,
      final Type method) {
    super(mv, securityControl, accessFlags, method);
  }

  @Override
  public void visitInsn(int opcode) {
    if (opcode == Opcodes.ARETURN) {
      processSanitizer();
    }
    super.visitInsn(opcode);
  }

  private void processSanitizer() {
    // Duplicate the return value on the stack
    mv.visitInsn(Opcodes.DUP);
    loadMarksAndCallHelper();
  }
}
