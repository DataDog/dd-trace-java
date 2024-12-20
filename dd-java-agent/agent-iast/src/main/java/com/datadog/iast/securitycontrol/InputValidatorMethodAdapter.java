package com.datadog.iast.securitycontrol;

import static com.datadog.iast.securitycontrol.SecurityControlMethodClassVisitor.LOGGER;
import static com.datadog.iast.securitycontrol.SecurityControlMethodClassVisitor.isPrimitive;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class InputValidatorMethodAdapter extends AbstractMethodAdapter {

  private final boolean isStatic;

  public InputValidatorMethodAdapter(
      final MethodVisitor mv,
      final SecurityControl securityControl,
      final int accessFlags,
      final Type method) {
    super(mv, securityControl, accessFlags, method);
    isStatic = isStatic();
  }

  @Override
  public void visitCode() {
    super.visitCode();
    processInputValidator();
  }

  private void processInputValidator() {
    boolean allParameters = securityControl.getParametersToMark() == null;
    Type[] types = method.getArgumentTypes();
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
      parametersCount += type.getSize();
    }
  }

  private void callInputValidation(int i) {
    // Load the parameter onto the stack
    mv.visitVarInsn(
        Opcodes.ALOAD,
        isStatic ? i : i + 1); // instance methods have this as first element in the stack
    loadMarksAndCallHelper();
  }
}
