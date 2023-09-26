package datadog.trace.bootstrap.instrumentation.shutdown;

import datadog.trace.bootstrap.Agent;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShutdownHelper {
  private static final Logger log = LoggerFactory.getLogger(ShutdownHelper.class);

  private static final Queue<Runnable> AGENT_SHUTDOWN_HOOKS = new ConcurrentLinkedQueue<>();

  public static void registerAgentShutdownHook(Runnable hook) {
    AGENT_SHUTDOWN_HOOKS.offer(hook);
  }

  public static void shutdownAgent() {
    log.debug("Executing agent shutdown hooks ...");
    while (!AGENT_SHUTDOWN_HOOKS.isEmpty()) {
      Runnable hook = AGENT_SHUTDOWN_HOOKS.poll();
      try {
        hook.run();
      } catch (Exception e) {
        log.error("Error while runnign agent shutdown hook", e);
      }
    }
    log.debug("Agent shutdown hooks executed");

    log.debug("Shutting down agent ...");
    Agent.shutdown(true);
    log.debug("Agent was properly shut down");
  }
}
