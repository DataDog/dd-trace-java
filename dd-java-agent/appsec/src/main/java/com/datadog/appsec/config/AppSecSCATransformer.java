package com.datadog.appsec.config;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClassFileTransformer for Supply Chain Analysis (SCA) vulnerability detection.
 *
 * <p>Instruments methods specified in the SCA configuration to detect when vulnerable third-party
 * library methods are called at runtime.
 *
 * <p>This is a POC implementation that logs method invocations. Future versions will report to the
 * Datadog backend with vulnerability details.
 */
public class AppSecSCATransformer implements ClassFileTransformer {

  private static final Logger log = LoggerFactory.getLogger(AppSecSCATransformer.class);

  private final Map<String, TargetMethods> targetsByClass;

  /**
   * Creates a new SCA transformer with the given instrumentation targets.
   *
   * @param config the SCA configuration containing instrumentation targets
   */
  public AppSecSCATransformer(AppSecSCAConfig config) {
    this.targetsByClass = buildTargetsMap(config);
    log.debug("Created SCA transformer with {} target classes", targetsByClass.size());
  }

  private Map<String, TargetMethods> buildTargetsMap(AppSecSCAConfig config) {
    Map<String, TargetMethods> map = new HashMap<>();

    if (config.instrumentationTargets == null) {
      return map;
    }

    for (AppSecSCAConfig.InstrumentationTarget target : config.instrumentationTargets) {
      if (target.className == null || target.methodName == null) {
        continue;
      }

      // Convert internal format (org/foo/Bar) to internal format (already is internal)
      String internalClassName = target.className;

      TargetMethods methods = map.computeIfAbsent(internalClassName, k -> new TargetMethods());
      methods.addMethod(target.methodName);
    }

    return map;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer)
      throws IllegalClassFormatException {

    if (className == null) {
      return null;
    }

    // Check if this class is a target
    TargetMethods targetMethods = targetsByClass.get(className);
    if (targetMethods == null) {
      return null; // Not a target class
    }

    try {
      log.debug("Instrumenting SCA target class: {}", className);
      return instrumentClass(classfileBuffer, className, targetMethods);
    } catch (Exception e) {
      log.error("Failed to instrument SCA target class: {}", className, e);
      return null; // Return null to keep original bytecode
    }
  }

  private byte[] instrumentClass(
      byte[] originalBytecode, String className, TargetMethods targetMethods) {
    ClassReader reader = new ClassReader(originalBytecode);
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

    ClassVisitor visitor = new SCAClassVisitor(writer, className, targetMethods);

    try {
      reader.accept(visitor, ClassReader.EXPAND_FRAMES);
      byte[] transformedBytecode = writer.toByteArray();
      log.info("Successfully instrumented SCA target class: {}", className);
      return transformedBytecode;
    } catch (Exception e) {
      log.error("Error during ASM transformation for class: {}", className, e);
      return null;
    }
  }

  /** ASM ClassVisitor that instruments methods matching SCA targets. */
  private static class SCAClassVisitor extends ClassVisitor {
    private final String className;
    private final TargetMethods targetMethods;

    SCAClassVisitor(ClassVisitor cv, String className, TargetMethods targetMethods) {
      super(Opcodes.ASM9, cv);
      this.className = className;
      this.targetMethods = targetMethods;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

      // Check if this method is a target
      if (targetMethods.contains(name)) {
        log.debug("Instrumenting SCA target method: {}::{}", className, name);
        return new SCAMethodVisitor(mv, className, name, descriptor);
      }

      return mv;
    }
  }

  /** ASM MethodVisitor that injects SCA detection logic at method entry. */
  private static class SCAMethodVisitor extends MethodVisitor {
    private final String className;
    private final String methodName;
    private final String descriptor;

    SCAMethodVisitor(MethodVisitor mv, String className, String methodName, String descriptor) {
      super(Opcodes.ASM9, mv);
      this.className = className;
      this.methodName = methodName;
      this.descriptor = descriptor;
    }

    @Override
    public void visitCode() {
      // Inject logging call at method entry
      // This is POC code - in production this would call a detection handler
      injectSCADetectionCall();
      super.visitCode();
    }

    private void injectSCADetectionCall() {
      // Generate bytecode equivalent to:
      // AppSecSCADetector.onMethodInvocation("className", "methodName", "descriptor");

      // Load the class name
      mv.visitLdcInsn(className);

      // Load the method name
      mv.visitLdcInsn(methodName);

      // Load the descriptor
      mv.visitLdcInsn(descriptor);

      // Call the static detection method in bootstrap classloader
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          "datadog/trace/bootstrap/instrumentation/appsec/AppSecSCADetector",
          "onMethodInvocation",
          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
          false);
    }
  }

  /** Helper class to store target methods for a class. */
  private static class TargetMethods {
    private final Map<String, Boolean> methods = new HashMap<>();

    void addMethod(String methodName) {
      methods.put(methodName, Boolean.TRUE);
    }

    boolean contains(String methodName) {
      return methods.containsKey(methodName);
    }
  }
}
