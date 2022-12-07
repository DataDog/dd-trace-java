package datadog.trace.util;

import datadog.trace.api.Platform;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Get PID in reasonably cross-platform way. */
@SuppressForbidden
public final class PidHelper {
  private static final Logger log = LoggerFactory.getLogger(PidHelper.class);

  private static Long PID;

  public static Long getPid() {
    return PID;
  }

  public static void supplyIfAbsent(Supplier<Long> supplier) {
    if (null == PID) {
      PID = supplier.get();
    }
  }

  static {
    if (Platform.isJavaVersionAtLeast(9)) {
      try {
        PID =
            ((Supplier<Long>)
                    Class.forName("datadog.trace.util.JDK9PidSupplier")
                        .getDeclaredConstructor()
                        .newInstance())
                .get();
      } catch (Throwable e) {
        log.debug("JDK9PidSupplier not available", e);
      }
    }
  }
}
