package datadog.trace.civisibility.source;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ByteCodeLinesResolver implements LinesResolver {

  private static final Logger log = LoggerFactory.getLogger(ByteCodeLinesResolver.class);

  private final DDCache<Class<?>, ClassMethodLines> methodLinesCache =
      DDCaches.newFixedSizeIdentityCache(16);

  @Nonnull
  @Override
  public Lines getMethodLines(@Nonnull Method method) {
    try {
      ClassMethodLines classMethodLines =
          methodLinesCache.computeIfAbsent(method.getDeclaringClass(), ClassMethodLines::parse);
      return classMethodLines.get(method);

    } catch (Exception e) {
      log.error("Could not determine method borders for {}", method, e);
      return Lines.EMPTY;
    }
  }

  @Nonnull
  @Override
  public Lines getClassLines(@Nonnull Class<?> clazz) {
    return Lines.EMPTY;
  }

  static final class ClassMethodLines {
    private final Map<String, MethodLinesRecorder> recordersByMethodFingerprint = new HashMap<>();

    public MethodLinesRecorder createRecorder(String methodFingerprint) {
      MethodLinesRecorder recorder = new MethodLinesRecorder();
      recordersByMethodFingerprint.put(methodFingerprint, recorder);
      return recorder;
    }

    public Lines get(Method method) {
      String methodFingerprint = getFingerprint(method);
      MethodLinesRecorder methodLinesRecorder = recordersByMethodFingerprint.get(methodFingerprint);
      if (methodLinesRecorder != null) {
        return new Lines(methodLinesRecorder.startLineNumber, methodLinesRecorder.finishLineNumber);
      } else {
        return Lines.EMPTY;
      }
    }

    public static ClassMethodLines parse(Class<?> clazz) {
      try {
        ClassMethodLines classMethodLines = new ClassMethodLines();
        try (InputStream classStream = Utils.getClassStream(clazz)) {
          ClassReader classReader = new ClassReader(classStream);
          MethodLocator methodLocator = new MethodLocator(classMethodLines);
          classReader.accept(methodLocator, ClassReader.SKIP_FRAMES);
        }
        return classMethodLines;

      } catch (Exception e) {
        // do not cache failure
        throw new RuntimeException(e);
      }
    }

    public static String getFingerprint(Method method) {
      String methodName = method.getName();
      String methodDescriptor = Type.getMethodDescriptor(method);
      return getFingerprint(methodName, methodDescriptor);
    }

    public static String getFingerprint(String methodName, String methodDescriptor) {
      return methodName + ';' + methodDescriptor;
    }
  }

  private static class MethodLocator extends ClassVisitor {
    private final ClassMethodLines classMethodLines;

    MethodLocator(ClassMethodLines classMethodLines) {
      super(Opcodes.ASM9);
      this.classMethodLines = classMethodLines;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      return classMethodLines.createRecorder(ClassMethodLines.getFingerprint(name, descriptor));
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
