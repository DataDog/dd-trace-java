package com.datadog.iast.securitycontrol;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import datadog.trace.api.iast.securitycontrol.SecurityControlType;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecurityControlMethodAdapter extends MethodVisitor {

  private static final Logger LOGGER = LoggerFactory.getLogger(SecurityControlMethodAdapter.class);

  public static final String HELPER =
      "datadog/trace/api/iast/securitycontrol/SecurityControlHelper";
  public static final String METHOD = "setSecureMarks";
  public static final String DESCRIPTOR = "(Ljava/lang/Object;I)V";
  private final MethodVisitor mv;
  private final SecurityControl securityControl;
  private final String desc;
  private final boolean isStatic;

  public SecurityControlMethodAdapter(
      final MethodVisitor mv,
      final SecurityControl securityControl,
      final String desc,
      boolean isStatic) {
    super(Opcodes.ASM9, mv);
    this.mv = mv;
    this.securityControl = securityControl;
    this.desc = desc;
    this.isStatic = isStatic;
  }

  @Override
  public void visitCode() {
    super.visitCode();
    if (securityControl.getType() == SecurityControlType.INPUT_VALIDATOR) {
      processInputValidator();
    }
  }

  @Override
  public void visitInsn(int opcode) {
    if (securityControl.getType() == SecurityControlType.SANITIZER) {
      // Only process the return value if is an Object as we are not tainting primitives
      if (opcode == Opcodes.ARETURN) {
        processSanitizer();
      } else {
        Type returnType = Type.getReturnType(desc);
        // no need to check primitives as we are not tainting them
        LOGGER.warn(
            "Sanitizers should not be used on primitive return types. Return type {}. Security control: {}",
            returnType.getClassName(),
            securityControl);
      }
    }
    super.visitInsn(opcode);
  }

  private void processSanitizer() {
    // Duplicate the return value on the stack
    mv.visitInsn(Opcodes.DUP);
    // Load the marks from securityControl onto the stack
    mv.visitLdcInsn(securityControl.getMarks());
    // Insert the call to setSecureMarks with the return value and marks as parameters
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, METHOD, DESCRIPTOR, false);
  }

  private void processInputValidator() {
    boolean allParameters = securityControl.getParametersToMark() == null;
    Type[] types = Type.getArgumentTypes(desc);
    int parametersCount = 0;
    for (int i = 0; i < types.length; i++) {
      Type type = types[i];
      boolean isPrimitive = isPrimitive(type);
      if (allParameters) {
        if (!isPrimitive) {
          callInputValidation(parametersCount);
        }
      } else if (securityControl.getParametersToMark().get(i)) {
        if (isPrimitive) {
          LOGGER.warn(
              "Input validators should not be used on primitive types. Parameter {} with type {} .Security control: {}",
              i,
              type.getClassName(),
              securityControl);
        } else {
          callInputValidation(parametersCount);
        }
      }
      parametersCount += types[i].getSize();
    }
  }

  private void callInputValidation(int i) {
    // Duplicate the parameter value on the stack
    mv.visitVarInsn(
        Opcodes.ALOAD,
        isStatic ? i : i + 1); // instance methods have this as first element in the stack
    // Load the marks from securityControl onto the stack
    mv.visitLdcInsn(securityControl.getMarks());
    // Insert the call to setSecureMarks with the parameter value and marks as parameters
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, METHOD, DESCRIPTOR, false);
  }

  private static boolean isPrimitive(Type type) {
    // Check if is a primitive type
    int sort = type.getSort();
    return sort >= Type.BOOLEAN && sort <= Type.DOUBLE;
  }
}
