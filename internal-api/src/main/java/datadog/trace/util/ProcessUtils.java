package datadog.trace.util;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.SystemProperties;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ProcessUtils {

  private static final Logger log = LoggerFactory.getLogger(ProcessUtils.class);

  /**
   * Attempts to get path to the JVM that is used by the current process.
   *
   * <p>This is done on the best effort basis: depending on the Java version, vendor, current OS or
   * other environment details this method might return either of the following:
   *
   * <ul>
   *   <li>Path to the {@code java} command that was used to start the process
   *   <li>Path to home dir of the JDK/JRE that the process uses
   *   <li>{@code null} if neither of the options above is available
   * </ul>
   *
   * @return Path to the {@code java} command, JDK/JRE home, or {@code null} if neither could be
   *     determined
   */
  @Nullable
  @SuppressForbidden
  public static String getCurrentJvmPath() {
    if (JavaVirtualMachine.isJavaVersionAtLeast(9)) {
      try {
        Supplier<String> jdk9Supplier =
            (Supplier<String>)
                Class.forName("datadog.trace.util.JDK9ExecutableSupplier")
                    .getDeclaredConstructor()
                    .newInstance();
        return jdk9Supplier.get();
      } catch (Throwable e) {
        log.debug("Could not get process executable path using JDK9ExecutableSupplier", e);
      }
    }

    // JDK/JRE home, does not include "bin/java" portion
    return SystemProperties.get("java.home");
  }

  private ProcessUtils() {}
}
