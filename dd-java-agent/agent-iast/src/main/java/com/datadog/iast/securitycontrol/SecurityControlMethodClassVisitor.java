package com.datadog.iast.securitycontrol;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.ASM8;

public class SecurityControlMethodClassVisitor extends ClassVisitor {

  private final SecurityControl securityControl;

  public SecurityControlMethodClassVisitor(final ClassWriter cw, final SecurityControl securityControl) {
    super(ASM8, cw);
    this.securityControl = securityControl;
  }

  @Override
  public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
    cv.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
    if (mv != null) {
      mv = new SecurityControlMethodAdapter(ASM8, mv, access, name, desc, securityControl);
    }
    return mv;
  }

  public void visitEnd() {
    cv.visitEnd();
  }

}
