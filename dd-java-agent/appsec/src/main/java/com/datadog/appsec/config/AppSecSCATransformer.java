package com.datadog.appsec.config;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClassFileTransformer for SCA vulnerability detection.
 *
 * <p>Instruments methods specified in the SCA configuration to detect when vulnerable third-party
 * library methods are called at runtime.
 *
 * <p>This transformer uses a Supplier to access the current configuration, allowing it to
 * automatically use updated configurations without needing to be reinstalled.
 *
 * <p>This is a POC implementation that logs method invocations. Future versions will report to the
 * Datadog backend with vulnerability details.
 */
public class AppSecSCATransformer implements ClassFileTransformer {

  private static final Logger log = LoggerFactory.getLogger(AppSecSCATransformer.class);

  private final Supplier<AppSecSCAConfig> configSupplier;

  /**
   * Creates a new SCA transformer that reads configuration dynamically.
   *
   * @param configSupplier supplier that provides the current SCA configuration
   */
  public AppSecSCATransformer(Supplier<AppSecSCAConfig> configSupplier) {
    this.configSupplier = configSupplier;
    log.debug("Created SCA transformer with dynamic config supplier");
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

    // Get current configuration dynamically
    AppSecSCAConfig config = configSupplier.get();
    if (config == null || config.vulnerabilities == null || config.vulnerabilities.isEmpty()) {
      return null; // No configuration or no vulnerabilities
    }

    // Check if this class is a target in the current config
    TargetMethods targetMethods = findTargetMethodsForClass(config, className);
    if (targetMethods == null) {
      return null; // Not a target class
    }

    try {
      log.debug("Instrumenting SCA target class: {}", className);
      return instrumentClass(classfileBuffer, className, targetMethods);
    } catch (Exception e) {
      log.debug("Failed to instrument SCA target class: {}", className, e);
      return null; // Return null to keep original bytecode
    }
  }

  /**
   * Finds target methods for a specific class in the current configuration.
   *
   * @param config the current SCA configuration
   * @param className the internal class name (e.g., "org/foo/Bar")
   * @return TargetMethods if this class is a target, null otherwise
   */
  private TargetMethods findTargetMethodsForClass(AppSecSCAConfig config, String className) {
    // Convert internal format (org/foo/Bar) to binary format (org.foo.Bar)
    String binaryClassName = className.replace('/', '.');

    TargetMethods targetMethods = null;

    for (AppSecSCAConfig.Vulnerability vulnerability : config.vulnerabilities) {
      // Check if this class is an external entrypoint
      if (vulnerability.externalEntrypoint != null
          && vulnerability.externalEntrypoint.className != null
          && vulnerability.externalEntrypoint.className.equals(binaryClassName)
          && vulnerability.externalEntrypoint.methods != null
          && !vulnerability.externalEntrypoint.methods.isEmpty()) {

        if (targetMethods == null) {
          targetMethods = new TargetMethods(vulnerability);
        }

        // Add all methods from the external entrypoint (it's a list)
        for (String methodName : vulnerability.externalEntrypoint.methods) {
          if (methodName != null && !methodName.isEmpty()) {
            targetMethods.addMethod(methodName);
          }
        }
      }
    }

    return targetMethods;
  }

  private byte[] instrumentClass(
      byte[] originalBytecode, String className, TargetMethods targetMethods) {
    ClassReader reader = new ClassReader(originalBytecode);
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

    ClassVisitor visitor = new SCAClassVisitor(writer, className, targetMethods);

    try {
      reader.accept(visitor, ClassReader.EXPAND_FRAMES);
      byte[] transformedBytecode = writer.toByteArray();
      log.debug("Successfully instrumented SCA target class: {}", className);
      return transformedBytecode;
    } catch (Exception e) {
      log.debug("Error during ASM transformation for class: {}", className, e);
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
        AppSecSCAConfig.Vulnerability vulnerability = targetMethods.getVulnerability();
        return new SCAMethodVisitor(mv, className, name, descriptor, vulnerability);
      }

      return mv;
    }
  }

  /** ASM MethodVisitor that injects SCA detection logic at method entry. */
  private static class SCAMethodVisitor extends MethodVisitor {
    private final String className;
    private final String methodName;
    private final String descriptor;
    private final AppSecSCAConfig.Vulnerability vulnerability;

    SCAMethodVisitor(
        MethodVisitor mv,
        String className,
        String methodName,
        String descriptor,
        AppSecSCAConfig.Vulnerability vulnerability) {
      super(Opcodes.ASM9, mv);
      this.className = className;
      this.methodName = methodName;
      this.descriptor = descriptor;
      this.vulnerability = vulnerability;
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
      // AppSecSCADetector.onMethodInvocation("className", "methodName", "descriptor", "advisory",
      // "cve");

      // Load the class name
      mv.visitLdcInsn(className);

      // Load the method name
      mv.visitLdcInsn(methodName);

      // Load the descriptor
      mv.visitLdcInsn(descriptor);

      // Load the advisory (GHSA ID)
      String advisory = vulnerability != null ? vulnerability.advisory : null;
      if (advisory != null) {
        mv.visitLdcInsn(advisory);
      } else {
        mv.visitInsn(Opcodes.ACONST_NULL);
      }

      // Load the CVE ID
      String cve = vulnerability != null ? vulnerability.cve : null;
      if (cve != null) {
        mv.visitLdcInsn(cve);
      } else {
        mv.visitInsn(Opcodes.ACONST_NULL);
      }

      // Call the static detection method in bootstrap classloader
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          "datadog/trace/bootstrap/instrumentation/appsec/AppSecSCADetector",
          "onMethodInvocation",
          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
          false);
    }
  }

  /** Helper class to store target methods and vulnerability metadata for a class. */
  private static class TargetMethods {
    private final AppSecSCAConfig.Vulnerability vulnerability;
    private final Map<String, Boolean> methods = new HashMap<>();

    TargetMethods(AppSecSCAConfig.Vulnerability vulnerability) {
      this.vulnerability = vulnerability;
    }

    void addMethod(String methodName) {
      methods.put(methodName, Boolean.TRUE);
    }

    boolean contains(String methodName) {
      return methods.containsKey(methodName);
    }

    AppSecSCAConfig.Vulnerability getVulnerability() {
      return vulnerability;
    }
  }
}
