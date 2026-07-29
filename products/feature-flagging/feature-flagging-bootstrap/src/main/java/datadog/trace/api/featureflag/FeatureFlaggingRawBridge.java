package datadog.trace.api.featureflag;

import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stable bootstrap bridge for provider-owned Feature Flagging implementations.
 *
 * <p>The bridge accepts raw UFC bytes and JDK values only. Provider-owned UFC model and evaluator
 * objects must not cross the application and agent classloader boundary.
 */
public final class FeatureFlaggingRawBridge {

  public interface ConfigurationListener {
    void accept(byte[] content);
  }

  private static final List<ConfigurationListener> CONFIGURATION_LISTENERS =
      new CopyOnWriteArrayList<>();
  private static final AtomicReference<byte[]> CURRENT_CONFIGURATION = new AtomicReference<>();

  private FeatureFlaggingRawBridge() {}

  public static void addConfigurationListener(final ConfigurationListener listener) {
    CONFIGURATION_LISTENERS.add(listener);
    final byte[] current = CURRENT_CONFIGURATION.get();
    if (current != null) {
      listener.accept(current.clone());
    }
  }

  public static void removeConfigurationListener(final ConfigurationListener listener) {
    CONFIGURATION_LISTENERS.remove(listener);
  }

  public static void dispatchConfiguration(final byte[] content) {
    final byte[] retained = content == null ? null : content.clone();
    CURRENT_CONFIGURATION.set(retained);
    for (final ConfigurationListener listener : CONFIGURATION_LISTENERS) {
      listener.accept(retained == null ? null : retained.clone());
    }
  }

  /** Signals that application code initialized a Feature Flagging provider. */
  public static void activate() {
    FeatureFlaggingGateway.activate();
  }

  public static void dispatchExposure(
      final long timestamp,
      final String allocationKey,
      final String flagKey,
      final String variantKey,
      final String targetingKey,
      final Map<String, Object> attributes) {
    final Map<String, Object> copiedAttributes =
        attributes == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    FeatureFlaggingGateway.dispatch(
        new ExposureEvent(
            timestamp,
            new Allocation(allocationKey),
            new Flag(flagKey),
            new Variant(variantKey),
            new Subject(targetingKey, copiedAttributes)));
  }

  public static void dispatchSpanSerialId(
      final int serialId, final boolean doLog, final String targetingKey) {
    FeatureFlaggingGateway.dispatch(SpanEnrichmentEvent.serialId(serialId, doLog, targetingKey));
  }

  public static void dispatchSpanRuntimeDefault(final String flagKey, final Object value) {
    FeatureFlaggingGateway.dispatch(SpanEnrichmentEvent.runtimeDefault(flagKey, value));
  }
}
