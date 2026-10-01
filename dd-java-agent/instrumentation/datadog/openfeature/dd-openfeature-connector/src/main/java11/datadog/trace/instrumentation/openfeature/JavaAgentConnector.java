package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.connector.ConfigurationSource;
import com.datadog.openfeature.internal.connector.Connector;
import com.datadog.openfeature.internal.connector.EventTransport;
import com.datadog.openfeature.internal.connector.HealthMetrics;
import com.datadog.openfeature.internal.connector.SpanEnricher;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import javax.annotation.Nullable;

/** The connector backed by the agent Feature Flags backend. */
public final class JavaAgentConnector implements Connector {
  private final FeatureFlaggingGateway.Backend backend;
  private final HealthMetrics healthMetrics;
  private final SpanEnricher spanEnricher;

  JavaAgentConnector(final FeatureFlaggingGateway.Backend backend) {
    this.backend = backend;
    this.healthMetrics = new AgentHealthMetrics(backend);
    this.spanEnricher = new AgentSpanEnricher(backend);
  }

  /**
   * Connects the SDK to the agent backend.
   *
   * @param fallback the connector to use if the agent backend is not started.
   * @return the agent connector, or the fallback one.
   */
  public static Connector connect(final Connector fallback) {
    final FeatureFlaggingGateway.Backend backend = FeatureFlaggingGateway.backend();
    return backend == null ? fallback : new JavaAgentConnector(backend);
  }

  @Nullable
  @Override
  public String setting(final String key) {
    return this.backend.setting(key);
  }

  @Nullable
  @Override
  public ConfigurationSource remoteConfiguration() {
    return this.backend.isRemoteConfigAvailable()
        ? new AgentConfigurationSource(this.backend)
        : null;
  }

  @Nullable
  @Override
  public EventTransport eventProxy() {
    return this.backend.isEventProxyAvailable() ? new AgentEventProxy(this.backend) : null;
  }

  @Override
  public HealthMetrics healthMetrics() {
    return this.healthMetrics;
  }

  @Override
  public SpanEnricher spanEnricher() {
    return this.spanEnricher;
  }
}
