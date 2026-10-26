package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import com.datadog.openfeature.internal.bridge.v1.SpanEnricher;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import javax.annotation.Nullable;

/** The bridge backed by the agent Feature Flags backend. */
public final class JavaAgentBridge implements AgentBridge {
  private final FeatureFlaggingGateway.Backend backend;
  private final HealthMetrics healthMetrics;
  private final SpanEnricher spanEnricher;

  JavaAgentBridge(final FeatureFlaggingGateway.Backend backend) {
    this.backend = backend;
    this.healthMetrics = new AgentHealthMetrics(backend);
    this.spanEnricher = new AgentSpanEnricher(backend);
  }

  /**
   * Connects the SDK to the agent backend.
   *
   * @param fallback the bridge to use if the agent backend is not started.
   * @return the agent bridge, or the fallback one.
   */
  public static AgentBridge connect(final AgentBridge fallback) {
    final FeatureFlaggingGateway.Backend backend = FeatureFlaggingGateway.backend();
    return backend == null ? fallback : new JavaAgentBridge(backend);
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

  @Override
  public ShutdownSource shutdown() {
    // TODO Flush the SDK through the agent shutdown. Until then, the SDK uses a JVM shutdown hook.
    return null;
  }
}
