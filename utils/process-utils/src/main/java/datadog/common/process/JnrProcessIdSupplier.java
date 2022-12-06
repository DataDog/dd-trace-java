package datadog.common.process;

import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class JnrProcessIdSupplier implements Supplier<Long> {
  private static final Logger log = LoggerFactory.getLogger(JnrProcessIdSupplier.class);

  @Override
  public Long get() {
    try {
      final POSIX posix = POSIXFactory.getPOSIX();
      return (long) posix.getpid();
    } catch (final Exception e) {
      log.debug("Cannot get PID through POSIX API, giving up", e);
    }

    return null;
  }
}
