package com.datadog.iast.securitycontrol;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class AbstractMethodAdapter extends MethodVisitor {

  private static final String HELPER =
      "datadog/trace/api/iast/securitycontrol/SecurityControlHelper";
  private static final String METHOD = "setSecureMarks";
  private static final String DESCRIPTOR = "(Ljava/lang/Object;I)V";
  protected final MethodVisitor mv;
  protected final SecurityControl securityControl;
  protected final int accessFlags;
  protected final Type method;

  public AbstractMethodAdapter(
      final MethodVisitor mv,
      final SecurityControl securityControl,
      final int accessFlags,
      final Type method) {
    super(Opcodes.ASM9, mv);
    this.mv = mv;
    this.securityControl = securityControl;
    this.accessFlags = accessFlags;
    this.method = method;
  }

  protected boolean isStatic() {
    return (accessFlags & Opcodes.ACC_STATIC) > 0;
  }

  /**
   * This method loads the current secure marks defined in the control and then calls the helper
   * method
   */
  protected void loadMarksAndCallHelper() {
    // Load the marks from securityControl onto the stack
    mv.visitLdcInsn(securityControl.getMarks());
    // Insert the call to setSecureMarks with the return value and marks as parameters
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, METHOD, DESCRIPTOR, false);
  }
}
