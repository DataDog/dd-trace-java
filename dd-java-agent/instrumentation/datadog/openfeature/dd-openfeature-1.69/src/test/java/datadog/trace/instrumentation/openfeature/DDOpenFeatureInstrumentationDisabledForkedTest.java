package datadog.trace.instrumentation.openfeature;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.AgentBridges;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "feature.flags.enabled", value = "false")
class DDOpenFeatureInstrumentationDisabledForkedTest extends AbstractInstrumentationTest {
  @AfterEach
  void unregisterBackend() {
    FeatureFlaggingGateway.register(null);
  }

  @Test
  void doesNotInstrumentWhenFeatureFlagsIsDisabled() {
    FeatureFlaggingGateway.register(mock(FeatureFlaggingGateway.Backend.class));
    assertSame(AgentBridge.NONE, AgentBridges.detect());
  }
}
