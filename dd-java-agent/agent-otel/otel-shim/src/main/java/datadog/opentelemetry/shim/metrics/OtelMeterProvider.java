package datadog.opentelemetry.shim.metrics;

import datadog.opentelemetry.shim.OtelInstrumentationScope;
import datadog.opentelemetry.shim.metrics.export.OtelMetricsVisitor;
import datadog.trace.util.Strings;
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

  @Override
  public Meter get(String instrumentationScopeName) {
    return getMeterShim(instrumentationScopeName, null, null);
  }

  @Override
  public MeterBuilder meterBuilder(String instrumentationScopeName) {
    return new OtelMeterBuilder(this, instrumentationScopeName);
  }

  public void collectMetrics(OtelMetricsVisitor visitor) {
    meters.forEach((scope, meter) -> meter.collect(visitor.visitMeter(scope)));
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
