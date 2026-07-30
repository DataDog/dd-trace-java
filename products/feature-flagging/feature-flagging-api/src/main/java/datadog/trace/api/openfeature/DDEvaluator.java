package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.util.AbstractMap;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Agent-backed entrypoint over the shared Feature Flagging evaluator. */
class DDEvaluator implements Evaluator, FeatureFlaggingGateway.ConfigListener {

  static final String METADATA_SPLIT_SERIAL_ID = "__dd_split_serial_id";
  static final String METADATA_DO_LOG = "__dd_do_log";

  private static final boolean SPAN_ENRICHMENT_ENABLED = SpanEnrichmentGate.isEnabled();

  private final Runnable configCallback;
  private final AtomicReference<ServerConfiguration> configuration = new AtomicReference<>();
  private final CountDownLatch initializationLatch = new CountDownLatch(1);
  private final OpenFeatureEvaluationAdapter evaluator =
      new OpenFeatureEvaluationAdapter(DDEvaluator::dispatchExposure, SPAN_ENRICHMENT_ENABLED);

  public DDEvaluator(final Runnable configCallback) {
    this.configCallback = configCallback;
  }

  @Override
  public boolean initialize(
      final long timeout, final TimeUnit unit, final EvaluationContext context) throws Exception {
    FeatureFlaggingGateway.activate();
    FeatureFlaggingGateway.addConfigListener(this);
    return initializationLatch.await(timeout, unit) || hasConfiguration();
  }

  @Override
  public boolean hasConfiguration() {
    return configuration.get() != null;
  }

  @Override
  public void shutdown() {
    FeatureFlaggingGateway.removeConfigListener(this);
  }

  @Override
  public void accept(final ServerConfiguration config) {
    configuration.set(config);
    if (config != null) {
      initializationLatch.countDown();
      configCallback.run();
    } else if (initializationLatch.getCount() == 0) {
      configCallback.run();
    }
  }

  @Override
  public <T> ProviderEvaluation<T> evaluate(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    return evaluator.evaluate(configuration.get(), target, key, defaultValue, context);
  }

  static <T> T mapValue(final Class<T> target, final Object value) {
    return OpenFeatureEvaluationAdapter.mapValue(target, value);
  }

  private static void dispatchExposure(
      final String flag,
      final String allocationKey,
      final String variantKey,
      final EvaluationContext context) {
    final ExposureEvent event =
        new ExposureEvent(
            System.currentTimeMillis(),
            new datadog.trace.api.featureflag.exposure.Allocation(allocationKey),
            new datadog.trace.api.featureflag.exposure.Flag(flag),
            new datadog.trace.api.featureflag.exposure.Variant(variantKey),
            new Subject(context.getTargetingKey(), flattenContext(context)));
    FeatureFlaggingGateway.dispatch(event);
  }

  static AbstractMap<String, Object> flattenContext(final EvaluationContext context) {
    final Set<String> keys = context.keySet();
    final HashMap<String, Object> result = new HashMap<>();
    final Set<Value> seen = new HashSet<>();
    for (final String key : keys) {
      final Deque<FlattenEntry> deque = new LinkedList<>();
      deque.push(new FlattenEntry(key, context.getValue(key)));
      while (!deque.isEmpty()) {
        final FlattenEntry entry = deque.pop();
        final Value value = entry.value;
        if (value == null || seen.add(value)) {
          if (value == null) {
            result.put(entry.key, null);
          } else if (value.isList()) {
            final List<Value> list = value.asList();
            for (int i = 0; i < list.size(); i++) {
              deque.push(new FlattenEntry(entry.key + "[" + i + "]", list.get(i)));
            }
          } else if (value.isStructure()) {
            final Structure structure = value.asStructure();
            for (final String property : structure.keySet()) {
              deque.push(
                  new FlattenEntry(entry.key + "." + property, structure.getValue(property)));
            }
          } else {
            result.put(entry.key, context.convertValue(value));
          }
        }
      }
    }
    return result;
  }

  private static final class FlattenEntry {
    private final String key;
    private final Value value;

    private FlattenEntry(final String key, final Value value) {
      this.key = key;
      this.value = value;
    }
  }
}
