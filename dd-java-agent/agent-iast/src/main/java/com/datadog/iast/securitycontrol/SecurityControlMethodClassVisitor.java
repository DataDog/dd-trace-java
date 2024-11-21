package com.datadog.iast.securitycontrol;

import static org.objectweb.asm.Opcodes.ASM8;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

public class SecurityControlMethodClassVisitor extends ClassVisitor {

  private final SecurityControl securityControl;

  public SecurityControlMethodClassVisitor(
      final ClassWriter cw, final SecurityControl securityControl) {
    super(ASM8, cw);
    this.securityControl = securityControl;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
    if (mv != null && shouldBeAdapted(name, desc)) {
      mv = new SecurityControlMethodAdapter(mv, securityControl, desc);
    }
    return mv;
  }

  public void visitEnd() {
    cv.visitEnd();
  }

  private boolean shouldBeAdapted(String name, String desc) {

    if (!securityControl.getMethod().equals(name)) {
      return false;
    }

    if (securityControl.getParameterTypes() == null) {
      return true;
    }

    Type[] types = Type.getArgumentTypes(desc);
    if (types.length != securityControl.getParameterTypes().length) {
      return false;
    }

    for (int i = 0; i < types.length; i++) {
      if (!types[i].getClassName().equals(securityControl.getParameterTypes()[i])) {
        return false;
      }
    }

    return true;
  }
}
