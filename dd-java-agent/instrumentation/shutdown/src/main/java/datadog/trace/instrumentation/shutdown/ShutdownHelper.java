package datadog.trace.instrumentation.shutdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShutdownHelper {
  private static final Logger log = LoggerFactory.getLogger(ShutdownHelper.class);

  public static void shutdownAgent() {
    log.debug("Shutting down agent ...");
    try {
      Class.forName("datadog.trace.bootstrap.Agent")
          .getMethod("shutdown", Boolean.TYPE)
          .invoke(null, true);
      log.debug("Agent was properly shut down");
    } catch (Throwable t) {
      log.warn("Failed to shutdown Agent", t);
    }
  }
}
