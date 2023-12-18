package datadog.trace.api;

import java.lang.instrument.Instrumentation;

/**
 * A subsystem of the agent (e.g. Telemetry). This is meant to be called exclusively from the agent
 * bootstrap classes. Each implementation is expected to be loaded a single time.
 */
public interface Subsystem {

  /**
   * Called when the agent is starting up. Implementations are responsible for: - Checking
   * configuration pre-requisites. - Never throw.
   */
  void maybeStart(Instrumentation inst, Object sco);

  /** Called when the agent is shutting down. */
  default void shutdown() {}

  class Noop implements Subsystem {

    public static final Noop INSTANCE = new Noop();

    @Override
    public void maybeStart(Instrumentation inst, Object sco) {}
  }
}
