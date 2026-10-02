package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.connector.SpanEnricher;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import javax.annotation.Nullable;

/** Reflects flag evaluations onto the agent local root span. */
public final class AgentSpanEnricher implements SpanEnricher {
  private final FeatureFlaggingGateway.Backend backend;

  AgentSpanEnricher(final FeatureFlaggingGateway.Backend backend) {
    this.backend = backend;
  }

  @Override
  public void serialId(
      final int serialId, final boolean doLog, @Nullable final String targetingKey) {
    this.backend.enrichSerialId(serialId, doLog, targetingKey);
  }

  @Override
  public void runtimeDefault(final String flagKey, @Nullable final Object defaultValue) {
    this.backend.enrichRuntimeDefault(flagKey, defaultValue);
  }
}
