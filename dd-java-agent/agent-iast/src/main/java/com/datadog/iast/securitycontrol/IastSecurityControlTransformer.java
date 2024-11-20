package com.datadog.iast.securitycontrol;

import datadog.trace.api.iast.securitycontrol.SecurityControl;

import java.lang.instrument.ClassFileTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public class IastSecurityControlTransformer implements ClassFileTransformer {

  private final SecurityControl securityControl;

  public IastSecurityControlTransformer(SecurityControl securityControl) {
    this.securityControl = securityControl;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    ClassReader cr = new ClassReader(classfileBuffer);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
    ClassVisitor cv = new SecurityControlMethodClassVisitor(cw, className);
    cr.accept(cv, 0);
  }
}
