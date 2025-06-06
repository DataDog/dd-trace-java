package datadog.trace.util.stacktrace;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.test.util.AnyStackRunner;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import sun.misc.JavaLangAccess;
import sun.misc.SharedSecrets;

public class StackWalkerTestUtil {

  public static final String DD_CLASS_NAME = "datadog.trace.SynthTestClass";

  public static final String DD_IAST_CLASS_NAME = "com.datadog.iast.SynthTestClass";

  public static final String NOT_DD_CLASS_NAME = "external.test.SynthTestClass";

  private StackWalkerTestUtil() {}

  public static boolean isRunningJDK8WithHotSpot() {
    try {
      JavaLangAccess access = SharedSecrets.getJavaLangAccess();
      return JavaVirtualMachine.isJavaVersion(8) && access != null;
    } catch (Throwable throwable) {
      return false;
    }
  }

  public static List<StackTraceElement> getStackWalkFrom(
      final StackWalker walker, final String clazz) {
    final AtomicReference<List<StackTraceElement>> result = new AtomicReference<>();
    AnyStackRunner.callWithinStack(
        clazz,
        () -> {
          result.set(walker.walk(s -> s.collect(Collectors.toList())));
        });
    return result.get();
  }
}
