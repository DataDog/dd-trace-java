package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.bridge.ShutdownSource;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;

/** Notifies the agent shutdown, before the JVM shutdown hooks run. */
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
