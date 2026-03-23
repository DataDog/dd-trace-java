package datadog.opentelemetry.shim.metrics;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricStorage;
import datadog.trace.util.Strings;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public final class OtelMeterProvider implements MeterProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelMeterProvider.class);
  private static final String DEFAULT_METER_NAME = "unknown";

  public static final MeterProvider INSTANCE = new OtelMeterProvider();

  /** Meter shims, indexed by instrumentation scope. */
  private final Map<OtelInstrumentationScope, OtelMeter> meters = new ConcurrentHashMap<>();

  private OtelMeterProvider() {
    // register attribute reader for class-loader where this provider is being used/injected
    OtelMetricStorage.registerAttributeReader(
        Attributes.class.getClassLoader(),
        (attributes, consumer) ->
            ((Attributes) attributes)
                .forEach((attribute, value) -> consumer.accept(attribute.getKey(), value)));
  }

  @Override
  public Meter get(String instrumentationScopeName) {
    return getMeterShim(instrumentationScopeName, null, null);
  }

  @Override
  public MeterBuilder meterBuilder(String instrumentationScopeName) {
    return new OtelMeterBuilder(this, instrumentationScopeName);
  }

  OtelMeter getMeterShim(
      String instrumentationScopeName,
      @Nullable String instrumentationScopeVersion,
      @Nullable String schemaUrl) {
    if (Strings.isBlank(instrumentationScopeName)) {
      LOGGER.debug("Meter requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_METER_NAME;
    }
    return meters.computeIfAbsent(
        new OtelInstrumentationScope(
            instrumentationScopeName, instrumentationScopeVersion, schemaUrl),
        OtelMeter::new);
  }
}
