package com.datadog.iast.securitycontrol;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import datadog.trace.api.iast.securitycontrol.SecurityControlType;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class SecurityControlMethodAdapter extends MethodVisitor {

  public static final String HELPER =
      "datadog/trace/api/iast/securitycontrol/SecurityControlHelper";
  public static final String METHOD = "setSecureMarks";
  public static final String DESCRIPTOR = "(Ljava/lang/Object;I)V";
  private final MethodVisitor mv;
  private final SecurityControl securityControl;
  private final String desc;

  public SecurityControlMethodAdapter(
      final MethodVisitor mv, final SecurityControl securityControl, final String desc) {
    super(Opcodes.ASM8, mv);
    this.mv = mv;
    this.securityControl = securityControl;
    this.desc = desc;
  }

  @Override
  public void visitInsn(int opcode) {
    // Check if the opcode is a return instruction
    if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
      if (securityControl.getType() == SecurityControlType.INPUT_VALIDATOR) {
        boolean allParameters = securityControl.getParametersToMark() == null;
        Type[] types = Type.getArgumentTypes(desc);
        for (int i = 0; i < types.length; i++) {
          if (allParameters || securityControl.getParametersToMark().contains(i)) {
            callInputValidation(i);
          }
        }
      } else { // SecurityControlType.SANITIZER
        // Duplicate the return value on the stack
        mv.visitInsn(Opcodes.DUP);
        // Load the marks from securityControl onto the stack
        mv.visitLdcInsn(securityControl.getMarks());
        // Insert the call to setSecureMarks with the return value and marks as parameters
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, METHOD, DESCRIPTOR, false);
      }
    }

    super.visitInsn(opcode);
  }

  private void callInputValidation(int i) {
    // Duplicate the parameter value on the stack
    mv.visitVarInsn(Opcodes.ALOAD, i);
    // Load the marks from securityControl onto the stack
    mv.visitLdcInsn(securityControl.getMarks());
    // Insert the call to setSecureMarks with the parameter value and marks as parameters
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, METHOD, DESCRIPTOR, false);
  }
}
