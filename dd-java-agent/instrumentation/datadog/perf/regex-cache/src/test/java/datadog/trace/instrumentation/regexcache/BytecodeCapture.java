package datadog.trace.instrumentation.regexcache;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

public final class BytecodeCapture implements ClassFileTransformer {

  private final String internalName;
  private volatile byte[] bytes;

  private BytecodeCapture(final String className) {
    this.internalName = className.replace('.', '/');
  }

  public static List<String> invokesAfterTransform(
      final Instrumentation instrumentation, final String className, final ClassLoader loader)
      throws ClassNotFoundException, UnmodifiableClassException {
    final Class<?> target = Class.forName(className, false, loader);
    final BytecodeCapture capture = new BytecodeCapture(className);
    instrumentation.addTransformer(capture, true);
    try {
      instrumentation.retransformClasses(target);
    } finally {
      instrumentation.removeTransformer(capture);
    }
    if (capture.bytes == null) {
      throw new IllegalStateException("Bytecode of " + className + " was not captured");
    }
    return invokes(capture.bytes);
  }

  @Override
  public byte[] transform(
      final ClassLoader loader,
      final String className,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain,
      final byte[] classfileBuffer) {
    if (internalName.equals(className)) {
      bytes = classfileBuffer.clone();
    }
    return null;
  }

  static List<String> invokes(final byte[] classFile) {
    final List<String> result = new ArrayList<>();
    new ClassReader(classFile)
        .accept(
            new ClassVisitor(Opcodes.ASM8) {
              @Override
              public MethodVisitor visitMethod(
                  final int access,
                  final String name,
                  final String descriptor,
                  final String signature,
                  final String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM8) {
                  @Override
                  public void visitMethodInsn(
                      final int opcode,
                      final String owner,
                      final String methodName,
                      final String methodDescriptor,
                      final boolean isInterface) {
                    result.add(owner + "." + methodName + " " + methodDescriptor);
                  }
                };
              }
            },
            ClassReader.SKIP_DEBUG);
    return result;
  }
}
