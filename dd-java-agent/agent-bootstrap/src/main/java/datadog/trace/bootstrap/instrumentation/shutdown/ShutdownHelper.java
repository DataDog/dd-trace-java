package datadog.trace.bootstrap.instrumentation.shutdown;

import datadog.trace.bootstrap.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShutdownHelper {
  private static final Logger log = LoggerFactory.getLogger(ShutdownHelper.class);

  public static volatile Runnable TELEMETRY_SHUTDOWN_HOOK;

  public static void shutdownAgent() {
    log.debug("Executing telemetry shutdown hook ...");
    if (TELEMETRY_SHUTDOWN_HOOK != null) {
      try {
        TELEMETRY_SHUTDOWN_HOOK.run();
        TELEMETRY_SHUTDOWN_HOOK = null;
      } catch (Exception e) {
        log.error("Error while running telemetry shutdown hook", e);
      }
    }
    log.debug("Telemetry shutdown hook executed");

    log.debug("Shutting down agent ...");
    Agent.shutdown(true);
    log.debug("Agent was properly shut down");
  }
}
