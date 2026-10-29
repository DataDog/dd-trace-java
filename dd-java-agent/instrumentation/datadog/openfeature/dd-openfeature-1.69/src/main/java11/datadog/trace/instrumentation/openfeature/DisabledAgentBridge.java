package datadog.trace.instrumentation.openfeature;

import static datadog.trace.api.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import com.datadog.openfeature.internal.bridge.v1.SpanEnricher;
import javax.annotation.Nullable;

/**
 * The bridge telling the SDK that the agent configuration disables Feature Flags, as the SDK cannot
 * read every agent configuration source (stable configuration, configuration file).
 */
public final class DisabledAgentBridge implements AgentBridge {
  static final DisabledAgentBridge INSTANCE = new DisabledAgentBridge();

  private DisabledAgentBridge() {}

  @Nullable
  @Override
  public String setting(final String key) {
    return FEATURE_FLAGS_ENABLED.equals(key) ? "false" : null;
  }

  @Nullable
  @Override
  public ConfigurationSource remoteConfiguration() {
    return null;
  }

  @Nullable
  @Override
  public EventTransport eventProxy() {
    return null;
  }

  @Override
  public HealthMetrics healthMetrics() {
    return HealthMetrics.NOOP;
  }

  @Override
  public SpanEnricher spanEnricher() {
    return SpanEnricher.NOOP;
  }

  @Nullable
  @Override
  public ShutdownSource shutdown() {
    return null;
  }
}
