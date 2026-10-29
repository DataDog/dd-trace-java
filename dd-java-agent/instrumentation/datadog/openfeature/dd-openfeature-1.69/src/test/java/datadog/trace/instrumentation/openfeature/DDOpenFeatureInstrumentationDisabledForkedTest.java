package datadog.trace.instrumentation.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.AgentBridges;
import com.datadog.openfeature.internal.config.Settings;
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
  void reportsTheAgentDisableToTheSdk() {
    FeatureFlaggingGateway.register(mock(FeatureFlaggingGateway.Backend.class));

    final AgentBridge bridge = AgentBridges.detect();

    assertEquals("false", bridge.setting("feature.flags.enabled"));
    assertNull(bridge.setting("feature.flags.configuration.source"));
    assertNull(bridge.remoteConfiguration());
    assertNull(bridge.eventProxy());
    assertNull(bridge.shutdown());
    assertFalse(Settings.load(bridge).isEnabled());
  }
}
