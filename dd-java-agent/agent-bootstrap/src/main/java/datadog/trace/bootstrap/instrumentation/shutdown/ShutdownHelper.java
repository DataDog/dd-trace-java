package datadog.trace.bootstrap.instrumentation.shutdown;

import datadog.trace.bootstrap.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShutdownHelper {
  private static final Logger log = LoggerFactory.getLogger(ShutdownHelper.class);

  public static void shutdownAgent() {
    log.debug("Shutting down agent ...");
    Agent.shutdown(true);
    log.debug("Agent was properly shut down");
  }
}
