package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;

/**
 * Notifies the agent shutdown, before the agent releases its Feature Flags resources: before the
 * JVM shutdown hooks when the tracing shutdown instrumentation runs, otherwise from a JVM shutdown
 * hook.
 */
public final class AgentShutdownSource implements ShutdownSource {
  private final FeatureFlaggingGateway.Backend backend;

  AgentShutdownSource(final FeatureFlaggingGateway.Backend backend) {
    this.backend = backend;
  }

  @Override
  public AutoCloseable subscribe(final Runnable listener) {
    return this.backend.subscribeShutdown(listener);
  }
}
