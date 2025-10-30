package com.datadog.featureflag;

import com.datadog.featureflag.exposure.Allocation;
import com.datadog.featureflag.exposure.ExposureEvent;
import com.datadog.featureflag.exposure.Flag;
import com.datadog.featureflag.exposure.Subject;
import com.datadog.featureflag.exposure.Variant;
import datadog.trace.api.featureflag.FeatureFlagEvaluator;
import datadog.trace.core.util.LRUCache;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * This class processes evaluations from a {@link FeatureFlagEvaluator} and records the resulting
 * exposures through a {@link ExposureWriter}. It uses an LRU cache to avoid sending duplicate
 * exposure events.
 */
public class ExposureWriterEvaluatorAdapter implements FeatureFlagEvaluator {

  private static final int DEFAULT_CACHE_CAPACITY = 50_000;

  private final ExposureWriter writer;
  private final FeatureFlagEvaluator delegate;
  private final Set<ExposureEvent> cache;

  public ExposureWriterEvaluatorAdapter(
      final ExposureWriter writer, final FeatureFlagEvaluator delegate) {
    this(DEFAULT_CACHE_CAPACITY, writer, delegate);
  }

  public ExposureWriterEvaluatorAdapter(
      final int cacheCapacity, final ExposureWriter writer, final FeatureFlagEvaluator delegate) {
    this.writer = writer;
    this.delegate = delegate;
    this.cache = Collections.newSetFromMap(new LRUCache<>(cacheCapacity));
  }

  @Override
  public Resolution<Boolean> evaluate(
      final String key, final Boolean defaultValue, final Context context) {
    final Resolution<Boolean> resolution = delegate.evaluate(key, defaultValue, context);
    writeResolution(context, resolution);
    return resolution;
  }

  @Override
  public Resolution<Integer> evaluate(
      final String key, final Integer defaultValue, final Context context) {
    final Resolution<Integer> resolution = delegate.evaluate(key, defaultValue, context);
    writeResolution(context, resolution);
    return resolution;
  }

  @Override
  public Resolution<Double> evaluate(
      final String key, final Double defaultValue, final Context context) {
    final Resolution<Double> resolution = delegate.evaluate(key, defaultValue, context);
    writeResolution(context, resolution);
    return resolution;
  }

  @Override
  public Resolution<String> evaluate(
      final String key, final String defaultValue, final Context context) {
    final Resolution<String> resolution = delegate.evaluate(key, defaultValue, context);
    writeResolution(context, resolution);
    return resolution;
  }

  @Override
  public Resolution<Object> evaluate(
      final String key, final Object defaultValue, final Context context) {
    final Resolution<Object> resolution = delegate.evaluate(key, defaultValue, context);
    writeResolution(context, resolution);
    return resolution;
  }

  private <E> void writeResolution(final Context context, final Resolution<E> resolution) {
    final String allocationKey = allocationKey(resolution);
    final String variantKey = resolution.getVariant();
    if (allocationKey == null || variantKey == null) {
      return;
    }
    final ExposureEvent event =
        new ExposureEvent(
            System.currentTimeMillis(),
            new Allocation(allocationKey),
            new Flag(resolution.getFlagKey()),
            new Variant(variantKey),
            new Subject(context.getTargetingKey(), flattenContext(context)));

    boolean writeEvent;
    synchronized (cache) {
      writeEvent = cache.add(event);
    }
    if (writeEvent) {
      writer.write(event);
    }
  }

  private AbstractMap<String, Object> flattenContext(final Context context) {
    if (context == null) {
      return null;
    }
    final Set<String> keys = context.keySet();
    final HashMap<String, Object> result = new HashMap<>(keys.size());
    for (final String key : keys) {
      final Object value = context.getValue(key);
      // TODO ignore nested elements for now as they are not supported by the backend
      if (value instanceof Integer
          || value instanceof Double
          || value instanceof Boolean
          || value instanceof String) {
        result.put(key, value);
      } else if (value == null) {
        result.put(key, null);
      }
    }
    return result;
  }

  private static String allocationKey(final Resolution<?> resolution) {
    final Map<String, Object> meta = resolution.getFlagMetadata();
    return meta == null ? null : (String) meta.get("allocationKey");
  }
}
