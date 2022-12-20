package datadog.trace.util;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Use standard API to retrieve PID on Java9+. */
public final class JDK9PidSupplier implements Supplier<Long> {
  private static final Logger log = LoggerFactory.getLogger(JDK9PidSupplier.class);

  @Override
  public Long get() {
    try {
      return ProcessHandle.current().pid();
    } catch (Throwable e) {
      log.debug("Cannot get PID through JVM API", e);
      return null;
    }
  }
}
