package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.connector.ConfigurationSource;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import java.util.function.Consumer;

/** Receives the flag configuration from the agent Remote Configuration client. */
public final class AgentConfigurationSource implements ConfigurationSource {
  private final FeatureFlaggingGateway.Backend backend;

  AgentConfigurationSource(final FeatureFlaggingGateway.Backend backend) {
    this.backend = backend;
  }

  @Override
  public AutoCloseable subscribe(final Consumer<byte[]> listener) {
    return this.backend.subscribeRemoteConfig(listener);
  }
}
