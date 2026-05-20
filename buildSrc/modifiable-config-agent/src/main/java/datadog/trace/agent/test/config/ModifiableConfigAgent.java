package datadog.trace.agent.test.config;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Test-only Java agent that rewrites the {@code INSTANCE} field of {@code
 * datadog.trace.api.Config} and {@code datadog.trace.api.InstrumenterConfig} to be public,
 * volatile, and non-final, so tests can swap the singleton with a freshly-built instance.
 *
 * <p>Unlike a JUnit 5 extension that uses ByteBuddy to retransform the classes, this agent runs
 * before any class is loaded, so the rewrite is guaranteed regardless of which class touches the
 * config first.
 */
public final class ModifiableConfigAgent {

  private static final String CONFIG = "datadog/trace/api/Config";
  private static final String INST_CONFIG = "datadog/trace/api/InstrumenterConfig";
  private static final String INSTANCE = "INSTANCE";

  private ModifiableConfigAgent() {}

  public static void premain(String args, Instrumentation inst) {
    inst.addTransformer(new InstanceFieldRewriter(), false);
  }

  static final class InstanceFieldRewriter implements ClassFileTransformer {
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
      if (!CONFIG.equals(className) && !INST_CONFIG.equals(className)) {
        return null;
      }
      try {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new InstanceFieldClassVisitor(writer), 0);
        return writer.toByteArray();
      } catch (Throwable t) {
        System.err.println(
            "[modifiable-config-agent] failed to rewrite " + className + ": " + t);
        return null;
      }
    }
  }

  static final class InstanceFieldClassVisitor extends ClassVisitor {
    InstanceFieldClassVisitor(ClassVisitor cv) {
      super(Opcodes.ASM9, cv);
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      if (INSTANCE.equals(name)) {
        int rewritten =
            (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL))
                | Opcodes.ACC_PUBLIC
                | Opcodes.ACC_VOLATILE;
        return super.visitField(rewritten, name, descriptor, signature, value);
      }
      return super.visitField(access, name, descriptor, signature, value);
    }
  }
}
