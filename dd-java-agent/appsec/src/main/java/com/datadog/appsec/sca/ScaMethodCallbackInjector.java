package com.datadog.appsec.sca;

import datadog.trace.api.internal.VisibleForTesting;
import java.util.List;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Injects SCA reachability callbacks at method entry points via ASM bytecode manipulation.
 *
 * <p>Given a map of method name to callback specs, modifies class bytecode to call {@code
 * ScaReachabilityCallback.onMethodHit} at the entry point of each watched method.
 */
final class ScaMethodCallbackInjector {

  private static final String CALLBACK_OWNER =
      "datadog/trace/bootstrap/appsec/sca/ScaReachabilityCallback";
  private static final String CALLBACK_METHOD = "onMethodHit";
  private static final String CALLBACK_DESC =
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V";

  private ScaMethodCallbackInjector() {}

  @VisibleForTesting
  static byte[] inject(
      byte[] classfileBuffer, Map<String, List<MethodCallbackSpec>> callbacksPerMethod) {
    ClassReader cr = new ClassReader(classfileBuffer);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cr.accept(new MethodCallbackClassVisitor(cw, callbacksPerMethod), ClassReader.EXPAND_FRAMES);
    return cw.toByteArray();
  }

  private static class MethodCallbackClassVisitor extends ClassVisitor {
    private final Map<String, List<MethodCallbackSpec>> callbacksPerMethod;

    MethodCallbackClassVisitor(
        ClassVisitor cv, Map<String, List<MethodCallbackSpec>> callbacksPerMethod) {
      super(OpenedClassReader.ASM_API, cv);
      this.callbacksPerMethod = callbacksPerMethod;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      List<MethodCallbackSpec> specs = callbacksPerMethod.get(name);
      if (specs == null || specs.isEmpty()) {
        return mv;
      }
      return new MethodEntryInjector(mv, specs);
    }
  }

  private static class MethodEntryInjector extends MethodVisitor {
    private final List<MethodCallbackSpec> specs;
    private boolean injected = false;

    MethodEntryInjector(MethodVisitor mv, List<MethodCallbackSpec> specs) {
      super(OpenedClassReader.ASM_API, mv);
      this.specs = specs;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      if (!injected) {
        injected = true;
        injectCallbacks(line);
      }
      super.visitLineNumber(line, start);
    }

    @Override
    public void visitInsn(int opcode) {
      ensureInjected();
      super.visitInsn(opcode);
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
      ensureInjected();
      super.visitVarInsn(opcode, varIndex);
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      ensureInjected();
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      ensureInjected();
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    private void ensureInjected() {
      if (!injected) {
        injected = true;
        injectCallbacks(1); // no debug info — use line 1 as placeholder
      }
    }

    private void injectCallbacks(int line) {
      // No dedup check here: retransformClasses() always starts from the original class bytes,
      // so the callback must be re-injected on every transformation pass. Deduplication of
      // actual runtime reports is handled by ScaReachabilityCallback.reported (bootstrap-side),
      // which persists across retransformations and prevents duplicate hits regardless of how
      // many times the class is retransformed.
      for (MethodCallbackSpec spec : specs) {
        mv.visitLdcInsn(spec.vulnId);
        mv.visitLdcInsn(spec.artifact);
        mv.visitLdcInsn(spec.version);
        mv.visitLdcInsn(spec.dotClassName);
        mv.visitLdcInsn(spec.methodName);
        mv.visitLdcInsn(line); // LDC handles the full int range; SIPUSH is limited to -32768..32767
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, CALLBACK_OWNER, CALLBACK_METHOD, CALLBACK_DESC, false);
      }
    }
  }

  /** Immutable spec for a single method-level callback to inject. */
  static final class MethodCallbackSpec {
    final String vulnId;
    final String artifact;
    final String version;
    final String dotClassName;
    final String methodName;

    MethodCallbackSpec(
        String vulnId, String artifact, String version, String dotClassName, String methodName) {
      this.vulnId = vulnId;
      this.artifact = artifact;
      this.version = version;
      this.dotClassName = dotClassName;
      this.methodName = methodName;
    }
  }
}
