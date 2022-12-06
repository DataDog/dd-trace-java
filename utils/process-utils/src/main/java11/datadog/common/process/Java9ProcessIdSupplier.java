package datadog.common.process;

import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;

import java.util.function.Supplier;

public class Java9ProcessIdSupplier implements Supplier<Long> {
  private static final Logger log = LoggerFactory.getLogger(Java9ProcessIdSupplier.class);

  @Override
  public Long get() {
    try {
      ProcessHandle
    } catch (final Exception e) {
      log.debug("Cannot get PID through JVM API, trying POSIX instead", e);
    }
    return null;
  }
}
