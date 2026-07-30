package datadog.trace.api.featureflag;

import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  private static final Object CONFIGURATION_LOCK = new Object();
  private static final List<ListenerRegistration> CONFIGURATION_LISTENERS = new ArrayList<>();
  private static byte[] currentConfiguration;
  private static long configurationVersion;

  private FeatureFlaggingRawBridge() {}

  public static void addConfigurationListener(final ConfigurationListener listener) {
    final ListenerRegistration registration = new ListenerRegistration(listener);
    final byte[] current;
    final long version;
    synchronized (CONFIGURATION_LOCK) {
      CONFIGURATION_LISTENERS.add(registration);
      current = currentConfiguration;
      version = configurationVersion;
    }
    if (current != null) {
      registration.deliver(version, current);
    }
  }

  public static void removeConfigurationListener(final ConfigurationListener listener) {
    synchronized (CONFIGURATION_LOCK) {
      for (int i = 0; i < CONFIGURATION_LISTENERS.size(); i++) {
        if (CONFIGURATION_LISTENERS.get(i).listener.equals(listener)) {
          CONFIGURATION_LISTENERS.remove(i);
          break;
        }
      }
    }
  }

  public static void dispatchConfiguration(final byte[] content) {
    final byte[] retained = content == null ? null : content.clone();
    final List<ListenerRegistration> listeners;
    final long version;
    synchronized (CONFIGURATION_LOCK) {
      currentConfiguration = retained;
      version = ++configurationVersion;
      listeners = new ArrayList<>(CONFIGURATION_LISTENERS);
    }
    for (final ListenerRegistration listener : listeners) {
      listener.deliver(version, retained);
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

  private static final class ListenerRegistration {
    private final ConfigurationListener listener;
    private long deliveredVersion = Long.MIN_VALUE;

    private ListenerRegistration(final ConfigurationListener listener) {
      this.listener = listener;
    }

    private synchronized void deliver(final long version, final byte[] content) {
      if (version <= deliveredVersion) {
        return;
      }
      deliveredVersion = version;
      listener.accept(content == null ? null : content.clone());
    }
  }
}
