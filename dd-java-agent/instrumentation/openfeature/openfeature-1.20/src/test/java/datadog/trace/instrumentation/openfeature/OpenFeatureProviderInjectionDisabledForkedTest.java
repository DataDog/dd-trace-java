package datadog.trace.instrumentation.openfeature;

import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.test.junit.utils.config.WithConfig;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.enabled", value = "false")
class OpenFeatureProviderInjectionDisabledForkedTest extends AbstractInstrumentationTest {

  @AfterEach
  void resetOpenFeature() {
    OpenFeatureAPI.getInstance().shutdown();
  }

  @Test
  void leavesNoOpProviderWithoutExplicitFeatureFlaggingConfiguration() {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();

    assertSame(NoOpProvider.class, api.getProvider().getClass());
  }
}
