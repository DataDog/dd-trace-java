package datadog.trace.bootstrap.instrumentation.ci.source;

import java.lang.reflect.Method;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.utility.OpenedClassReader;
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

      return new MethodLines(
          methodLocator.methodLinesRecorder.startLineNumber,
          methodLocator.methodLinesRecorder.finishLineNumber);
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
      super(OpenedClassReader.ASM_API);
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
      super(OpenedClassReader.ASM_API);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      startLineNumber = Math.min(startLineNumber, line);
      finishLineNumber = Math.max(finishLineNumber, line);
    }
  }
}
