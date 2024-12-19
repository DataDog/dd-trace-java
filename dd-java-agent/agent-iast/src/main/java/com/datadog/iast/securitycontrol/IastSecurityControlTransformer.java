package com.datadog.iast.securitycontrol;

import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

import datadog.trace.api.iast.securitycontrol.SecurityControl;
import java.lang.instrument.ClassFileTransformer;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastSecurityControlTransformer implements ClassFileTransformer {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(IastSecurityControlTransformer.class);

  private final Map<String, List<SecurityControl>> securityControls;

  public IastSecurityControlTransformer(Map<String, List<SecurityControl>> securityControls) {
    this.securityControls = securityControls;
  }

  @Override
  @Nullable
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      java.security.ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (!securityControls.containsKey(className)) {
      return null; // Do not transform classes that are not in the classFilter
    }
    List<SecurityControl> match = securityControls.get(className);
    if (match == null || match.isEmpty()) {
      return null; // Do not transform classes that do not have a security control
    }
    try {
      ClassReader cr = new ClassReader(classfileBuffer);
      ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
      ClassVisitor cv = new SecurityControlMethodClassVisitor(cw, match);
      cr.accept(cv, SKIP_DEBUG | SKIP_FRAMES);
      return cw.toByteArray();
    } catch (Throwable e) {
      LOGGER.warn("Failed to transform class: {}", className, e);
      return null;
    }
  }
}
