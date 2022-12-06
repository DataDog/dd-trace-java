package datadog.common.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class Java9ProcessIdSupplier implements Supplier<Long> {
  private static final Logger log = LoggerFactory.getLogger(Java9ProcessIdSupplier.class);

  @Override
  public Long get() {
    try {
      final Class<?> processHandler = Class.forName("java.lang.ProcessHandle");
      final Object object = processHandler.getMethod("current").invoke(null);
      return (Long) processHandler.getMethod("pid").invoke(object);
    } catch (final Exception e) {
      log.debug("Cannot get PID through JVM API, trying POSIX instead", e);
    }
    return null;
  }
}
