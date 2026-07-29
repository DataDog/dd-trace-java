package datadog.trace.api.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeatureFlaggingRawBridgeTest {

  @AfterEach
  void clear() {
    FeatureFlaggingRawBridge.dispatchConfiguration(null);
  }

  @Test
  void dispatchesUpdatesAndDeletion() {
    final AtomicReference<byte[]> received = new AtomicReference<>();
    final FeatureFlaggingRawBridge.ConfigurationListener listener = received::set;
    FeatureFlaggingRawBridge.addConfigurationListener(listener);
    try {
      FeatureFlaggingRawBridge.dispatchConfiguration("one".getBytes(UTF_8));
      assertArrayEquals("one".getBytes(UTF_8), received.get());

      FeatureFlaggingRawBridge.dispatchConfiguration(null);
      assertNull(received.get());
    } finally {
      FeatureFlaggingRawBridge.removeConfigurationListener(listener);
    }
  }

  @Test
  void sendsRetainedConfigurationToLateListener() {
    FeatureFlaggingRawBridge.dispatchConfiguration("retained".getBytes(UTF_8));
    final AtomicReference<byte[]> received = new AtomicReference<>();
    final FeatureFlaggingRawBridge.ConfigurationListener listener = received::set;

    FeatureFlaggingRawBridge.addConfigurationListener(listener);
    try {
      assertEquals("retained", new String(received.get(), UTF_8));
    } finally {
      FeatureFlaggingRawBridge.removeConfigurationListener(listener);
    }
  }

  @Test
  void adaptsPrimitiveTelemetryToLegacyBridgeTypes() {
    final AtomicBoolean activated = new AtomicBoolean();
    final AtomicReference<ExposureEvent> exposure = new AtomicReference<>();
    final AtomicReference<SpanEnrichmentEvent> span = new AtomicReference<>();
    final FeatureFlaggingGateway.ActivationListener activationListener = () -> activated.set(true);
    final FeatureFlaggingGateway.ExposureListener exposureListener = exposure::set;
    final FeatureFlaggingGateway.SpanEnrichmentListener spanListener = span::set;
    FeatureFlaggingGateway.addActivationListener(activationListener);
    FeatureFlaggingGateway.addExposureListener(exposureListener);
    FeatureFlaggingGateway.addSpanEnrichmentListener(spanListener);
    try {
      FeatureFlaggingRawBridge.activate();
      assertTrue(activated.get());

      final Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("country", "US");
      FeatureFlaggingRawBridge.dispatchExposure(
          123, "allocation", "flag", "variant", "subject", attributes);
      attributes.put("country", "changed");
      assertEquals(123, exposure.get().timestamp);
      assertEquals("allocation", exposure.get().allocation.key);
      assertEquals("flag", exposure.get().flag.key);
      assertEquals("variant", exposure.get().variant.key);
      assertEquals("subject", exposure.get().subject.id);
      assertEquals("US", exposure.get().subject.attributes.get("country"));
      assertThrowsUnsupportedMutation(exposure.get().subject.attributes);

      FeatureFlaggingRawBridge.dispatchSpanSerialId(7, true, "subject");
      assertTrue(span.get().hasSerialId());
      assertEquals(7, span.get().serialId());
      assertTrue(span.get().doLog());
      assertEquals("subject", span.get().targetingKey());

      FeatureFlaggingRawBridge.dispatchSpanRuntimeDefault("flag", 42);
      assertEquals("flag", span.get().flagKey());
      assertEquals(42, span.get().defaultValue());

      FeatureFlaggingRawBridge.dispatchExposure(124, "allocation", "flag", "variant", null, null);
      assertTrue(exposure.get().subject.attributes.isEmpty());
    } finally {
      FeatureFlaggingGateway.removeActivationListener(activationListener);
      FeatureFlaggingGateway.removeExposureListener(exposureListener);
      FeatureFlaggingGateway.removeSpanEnrichmentListener(spanListener);
    }
  }

  private static void assertThrowsUnsupportedMutation(final Map<String, Object> attributes) {
    try {
      attributes.put("new", true);
    } catch (final UnsupportedOperationException expected) {
      return;
    }
    throw new AssertionError("Expected immutable exposure attributes");
  }
}
