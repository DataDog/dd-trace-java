package datadog.trace.util;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JDK9ExecutableSupplier implements Supplier<String> {
  private static final Logger log = LoggerFactory.getLogger(JDK9ExecutableSupplier.class);

  @Override
  public String get() {
    try {
      return ProcessHandle.current().info().command().orElse(null);
    } catch (Throwable e) {
      log.debug("Cannot get PID through JVM API", e);
      return null;
    }
  }
}
