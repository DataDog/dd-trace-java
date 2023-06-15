package datadog.trace.util;

import datadog.trace.api.Platform;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ProcessUtils {

  private static final Logger log = LoggerFactory.getLogger(ProcessUtils.class);

  public static String getCurrentExecutablePath() {
    if (Platform.isJavaVersionAtLeast(9)) {
      try {
        Supplier<String> jdk9Supplier =
            (Supplier<String>)
                Class.forName("datadog.trace.util.JDK9ExecutableSupplier")
                    .getDeclaredConstructor()
                    .newInstance();
        return jdk9Supplier.get();
      } catch (Throwable e) {
        log.debug("JDK9ExecutableSupplier not available", e);
      }
    }

    // JDK/JRE home, does not include "bin/java" portion
    return System.getProperty("java.home");
  }

  private ProcessUtils() {}
}
