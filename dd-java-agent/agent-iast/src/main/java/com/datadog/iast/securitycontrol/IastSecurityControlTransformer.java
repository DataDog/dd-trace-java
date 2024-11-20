package com.datadog.iast.securitycontrol;

import datadog.trace.api.iast.securitycontrol.SecurityControl;

import java.lang.instrument.ClassFileTransformer;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;


public class IastSecurityControlTransformer implements ClassFileTransformer {

  private final  List<SecurityControl> securityControls;
  private final Set<String> classFilter;

  public IastSecurityControlTransformer(List<SecurityControl> securityControls) {
    this.securityControls = securityControls;
    this.classFilter = securityControls.stream().map(SecurityControl::getClassName).collect(Collectors.toSet());
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (classFilter.contains(className)) {
      return null; // Do not transform classes that are not in the classFilter
    }
    ClassReader cr = new ClassReader(classfileBuffer);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
    ClassVisitor cv = new SecurityControlMethodClassVisitor(cw, getSecurityControl(className));
    cr.accept(cv, 0);
    return cw.toByteArray();
  }

  //TODO remove this and change structure to Map instead of List if this approach works
  private SecurityControl getSecurityControl(final String className) {
    return securityControls.stream().filter(sc -> sc.getClassName().equals(className)).findFirst().orElse(null);
  }
}
