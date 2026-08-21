package datadog.trace.instrumentation.openfeature;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.test.junit.utils.config.WithConfig;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.enabled", value = "false")
@WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
@WithConfig(key = "trace.openfeature.enabled", value = "false")
class OpenFeatureProviderInjectionIntegrationDisabledForkedTest
    extends AbstractInstrumentationTest {

  @AfterEach
  void resetOpenFeature() {
    OpenFeatureAPI.getInstance().shutdown();
  }

  @Test
  void honorsOpenFeatureInstrumentationKillswitch() {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();

    assertSame(NoOpProvider.class, api.getProvider().getClass());
  }
}
