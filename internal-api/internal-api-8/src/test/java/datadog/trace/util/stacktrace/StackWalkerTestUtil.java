package datadog.trace.util.stacktrace;

import datadog.trace.api.Platform;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import sun.misc.JavaLangAccess;
import sun.misc.SharedSecrets;

public class StackWalkerTestUtil {

  private StackWalkerTestUtil() {}

  public static boolean isRunningJDK8WithHotSpot() {
    try {
      JavaLangAccess access = SharedSecrets.getJavaLangAccess();
      return Platform.isJavaVersion(8) && access != null;
    } catch (Throwable throwable) {
      return false;
    }
  }

  public static List<StackTraceElement> toList(final Stream<StackTraceElement> stack) {
    return stack.collect(Collectors.toList());
  }
}
