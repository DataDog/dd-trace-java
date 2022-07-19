package datadog.trace.util.stacktrace;

import datadog.trace.api.Platform;
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
}
