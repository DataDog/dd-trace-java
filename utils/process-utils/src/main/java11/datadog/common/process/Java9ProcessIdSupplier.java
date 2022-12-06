package datadog.common.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class Java9ProcessIdSupplier implements Supplier<Long> {
  private static final Logger log = LoggerFactory.getLogger(Java9ProcessIdSupplier.class);

  @Override
  public Long get() {
    try {
      return ProcessHandle.current().pid();
    } catch (final Exception e) {
      log.debug("Cannot get PID through JVM API", e);
    }
    return null;
  }
}
