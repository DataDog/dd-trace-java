package com.datadog.iast.securitycontrol;

import static org.objectweb.asm.Opcodes.ASM8;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import java.util.List;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

public class SecurityControlMethodClassVisitor extends ClassVisitor {

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
      return new SecurityControlMethodAdapter(mv, match, desc);
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
}
