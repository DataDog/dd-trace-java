package com.datadog.iast.securitycontrol;

import static org.objectweb.asm.Opcodes.ASM8;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import datadog.trace.api.iast.securitycontrol.SecurityControlType;
import java.util.List;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecurityControlMethodClassVisitor extends ClassVisitor {

  static final Logger LOGGER = LoggerFactory.getLogger(SecurityControlMethodClassVisitor.class);

  private final List<SecurityControl> securityControls;

  public SecurityControlMethodClassVisitor(
      final ClassWriter cw, final List<SecurityControl> securityControls) {
    super(ASM8, cw);
    this.securityControls = securityControls;
  }

  @Override
  @Nullable
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
    if (mv == null) {
      return null;
    }
    SecurityControl match = null;
    for (SecurityControl securityControl : securityControls) {
      if (shouldBeAdapted(securityControl, name, desc)) {
        match = securityControl;
        break;
      }
    }
    if (match != null) {
      final Type method = Type.getMethodType(desc);
      if (match.getType() == SecurityControlType.SANITIZER) {
        mv = adaptSanitizer(mv, match, access, method);
      } else {
        mv = adaptInputValidator(mv, match, access, method);
      }
    }
    return mv;
  }

  public void visitEnd() {
    cv.visitEnd();
  }

  private boolean shouldBeAdapted(SecurityControl securityControl, String name, String desc) {

    if (!securityControl.getMethod().equals(name)) {
      return false;
    }

    if (securityControl.getParameterTypes() == null) {
      return true;
    }

    Type[] types = Type.getArgumentTypes(desc);
    if (types.length != securityControl.getParameterTypes().size()) {
      return false;
    }

    for (int i = 0; i < types.length; i++) {
      if (!types[i].getClassName().equals(securityControl.getParameterTypes().get(i))) {
        return false;
      }
    }

    return true;
  }

  private MethodVisitor adaptSanitizer(
      final MethodVisitor mv,
      final SecurityControl control,
      final int accessFlags,
      final Type method) {
    if (isPrimitive(method.getReturnType())) {
      // no need to check primitives as we are not tainting them
      LOGGER.warn(
          "Sanitizers should not be used on primitive return types. Return type {}. Security control: {}",
          method.getReturnType().getClassName(),
          control);
      return mv;
    }
    return new SanitizerMethodAdapter(mv, control, accessFlags, method);
  }

  private MethodVisitor adaptInputValidator(
      final MethodVisitor mv,
      final SecurityControl control,
      final int accessFlags,
      final Type method) {
    return new InputValidatorMethodAdapter(mv, control, accessFlags, method);
  }

  public static boolean isPrimitive(final Type type) {
    // Check if is a primitive type
    int sort = type.getSort();
    return sort >= Type.BOOLEAN && sort <= Type.DOUBLE;
  }
}
