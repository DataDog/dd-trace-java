package datadog.trace.agent.tooling;

import java.util.function.Supplier;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Use POSIX API to retrieve PID on Java8. */
public final class PosixPidSupplier implements Supplier<Long> {
  private static final Logger log = LoggerFactory.getLogger(PosixPidSupplier.class);

  @Override
  public Long get() {
    try {
      final POSIX posix = POSIXFactory.getPOSIX();
      return (long) posix.getpid();
    } catch (Throwable e) {
      log.debug("Cannot get PID through POSIX API", e);
      return null;
    }
  }
}
