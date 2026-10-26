package datadog.trace.instrumentation.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.AgentBridges;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventProxyUnavailableException;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Bootstrap types are not allowed in test class signatures, so the backend is a local variable.
class DDOpenFeatureInstrumentationTest extends AbstractInstrumentationTest {
  @AfterEach
  void unregisterBackend() {
    FeatureFlaggingGateway.register(null);
  }

  @Test
  void detectsTheAgentBridge() {
    final FeatureFlaggingGateway.Backend backend = mock(FeatureFlaggingGateway.Backend.class);
    FeatureFlaggingGateway.register(backend);
    assertInstanceOf(JavaAgentBridge.class, AgentBridges.detect());
  }

  @Test
  void keepsTheDefaultBridgeWithoutBackend() {
    assertSame(AgentBridge.NONE, AgentBridges.detect());
  }

  @Test
  void delegatesSettingsAndHealthMetrics() {
    final FeatureFlaggingGateway.Backend backend = mock(FeatureFlaggingGateway.Backend.class);
    FeatureFlaggingGateway.register(backend);
    when(backend.setting("site")).thenReturn("datadoghq.eu");
    final AgentBridge bridge = AgentBridges.detect();

    assertEquals("datadoghq.eu", bridge.setting("site"));
    bridge.healthMetrics().count("metric", 2, "reason");
    verify(backend).count("metric", 2, "reason");
  }

  @Test
  void exposesRemoteConfigurationOnlyWhenAvailable() throws Exception {
    final FeatureFlaggingGateway.Backend backend = mock(FeatureFlaggingGateway.Backend.class);
    FeatureFlaggingGateway.register(backend);
    final AgentBridge bridge = AgentBridges.detect();
    assertNull(bridge.remoteConfiguration());

    when(backend.isRemoteConfigAvailable()).thenReturn(true);
    final AutoCloseable subscription = mock(AutoCloseable.class);
    when(backend.subscribeRemoteConfig(any())).thenReturn(subscription);
    final ConfigurationSource source = bridge.remoteConfiguration();
    final Consumer<byte[]> listener = content -> {};

    assertSame(subscription, source.subscribe(listener));
    verify(backend).subscribeRemoteConfig(listener);
  }

  @Test
  void postsThroughTheEventProxyOnlyWhenAvailable() throws Exception {
    final FeatureFlaggingGateway.Backend backend = mock(FeatureFlaggingGateway.Backend.class);
    FeatureFlaggingGateway.register(backend);
    final AgentBridge bridge = AgentBridges.detect();
    assertNull(bridge.eventProxy());

    when(backend.isEventProxyAvailable()).thenReturn(true);
    final EventTransport proxy = bridge.eventProxy();
    final byte[] payload = "{}".getBytes(UTF_8);
    when(backend.postEvent("exposures", payload)).thenReturn(true);
    proxy.post("exposures", payload);
    verify(backend).postEvent("exposures", payload);

    when(backend.postEvent("exposures", payload)).thenReturn(false);
    assertThrows(EventProxyUnavailableException.class, () -> proxy.post("exposures", payload));
  }

  @Test
  void delegatesSpanEnrichment() {
    final FeatureFlaggingGateway.Backend backend = mock(FeatureFlaggingGateway.Backend.class);
    FeatureFlaggingGateway.register(backend);
    final AgentBridge bridge = AgentBridges.detect();

    bridge.spanEnricher().serialId(42, true, "user");
    bridge.spanEnricher().runtimeDefault("flag", "default");

    verify(backend).enrichSerialId(42, true, "user");
    verify(backend).enrichRuntimeDefault("flag", "default");
  }
}
