package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.connector.HealthMetrics;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import javax.annotation.Nullable;

/** Reports the SDK health counters through the agent telemetry. */
public final class AgentHealthMetrics implements HealthMetrics {
  private final FeatureFlaggingGateway.Backend backend;

  AgentHealthMetrics(final FeatureFlaggingGateway.Backend backend) {
    this.backend = backend;
  }

  @Override
  public void count(final String metric, final long value, @Nullable final String reason) {
    this.backend.count(metric, value, reason);
  }
}
