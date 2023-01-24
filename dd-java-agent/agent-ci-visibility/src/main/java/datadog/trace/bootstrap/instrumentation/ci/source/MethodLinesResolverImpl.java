package datadog.trace.bootstrap.instrumentation.ci.source;

import java.lang.reflect.Method;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MethodLinesResolverImpl implements MethodLinesResolver {

  private static final Logger log = LoggerFactory.getLogger(MethodLinesResolverImpl.class);

  @Override
  public MethodLines getLines(Method method) {
    try {
      String methodName = method.getName();
      String methodDescriptor = Type.getMethodDescriptor(method);
      MethodLocator methodLocator = new MethodLocator(methodName, methodDescriptor);

      Class<?> declaringClass = method.getDeclaringClass();
      String declaringClassName = declaringClass.getName();
      ClassReader classReader = new ClassReader(declaringClassName);
      classReader.accept(methodLocator, ClassReader.SKIP_FRAMES);

      MethodLinesRecorder methodLinesRecorder = methodLocator.methodLinesRecorder;
      return new MethodLines(
          methodLinesRecorder.startLineNumber, methodLinesRecorder.finishLineNumber);
    } catch (Exception e) {
      log.error("Could not determine method borders for {}", method, e);
      return MethodLines.EMPTY;
    }
  }

  private static class MethodLocator extends ClassVisitor {
    private final String methodName;
    private final String methodDescriptor;
    private final MethodLinesRecorder methodLinesRecorder;

    MethodLocator(String methodName, String methodDescriptor) {
      super(Opcodes.ASM9);
      this.methodName = methodName;
      this.methodDescriptor = methodDescriptor;
      methodLinesRecorder = new MethodLinesRecorder();
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if (name.equals(methodName) && descriptor.equals(methodDescriptor)) {
        return methodLinesRecorder;
      } else {
        return null;
      }
    }
  }

  private static class MethodLinesRecorder extends MethodVisitor {
    private int startLineNumber = Integer.MAX_VALUE;
    private int finishLineNumber = Integer.MIN_VALUE;

    MethodLinesRecorder() {
      super(Opcodes.ASM9);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      startLineNumber = Math.min(startLineNumber, line);
      finishLineNumber = Math.max(finishLineNumber, line);
    }
  }
}
