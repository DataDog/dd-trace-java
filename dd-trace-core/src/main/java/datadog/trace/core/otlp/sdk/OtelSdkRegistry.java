package datadog.trace.core.otlp.sdk;

import datadog.trace.bootstrap.otel.api.common.Attributes;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentBuilder;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricStorage;
import datadog.trace.bootstrap.otel.metrics.data.OtelRunnableObservable;
import java.util.function.Function;

/** Registers {@code otel.sdk.*} instruments directly on the bootstrap metric registry. */
final class OtelSdkRegistry {
  /** Instrumentation scope of every {@code otel.sdk.*} metric */
  static final OtelInstrumentationScope SCOPE =
      new OtelInstrumentationScope("datadog.trace.sdk", null, null);

  static {
    OtelMetricStorage.registerAttributeReader(
        Attributes.class.getClassLoader(),
        (attributes, visitor) ->
            ((Attributes) attributes)
                .forEach((a, v) -> visitor.visitAttribute(a.getType().ordinal(), a.getKey(), v)));
  }

  private OtelSdkRegistry() {}

  /** Registers (or returns the already registered) storage for the instrument. */
  static OtelMetricStorage storage(
      OtelInstrumentBuilder builder,
      boolean observable,
      String description,
      String unit,
      Function<OtelInstrumentDescriptor, OtelMetricStorage> storageFactory) {
    builder.setDescription(description);
    builder.setUnit(unit);
    return OtelMetricRegistry.INSTANCE.registerStorage(
        SCOPE, observable ? builder.observableDescriptor() : builder.descriptor(), storageFactory);
  }

  static void observe(Runnable callback) {
    OtelMetricRegistry.INSTANCE.registerObservable(SCOPE, new OtelRunnableObservable(callback));
  }
}
