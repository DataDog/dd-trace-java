package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelMeterBuilder implements MeterBuilder {
  private final OtelMeterProvider meterProvider;

  private final String instrumentationScopeName;
  @Nullable private String instrumentationScopeVersion;
  @Nullable private String schemaUrl;

  OtelMeterBuilder(OtelMeterProvider meterProvider, String instrumentationScopeName) {
    this.meterProvider = meterProvider;
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  public MeterBuilder setInstrumentationVersion(String instrumentationScopeVersion) {
    this.instrumentationScopeVersion = instrumentationScopeVersion;
    return this;
  }

  @Override
  public MeterBuilder setSchemaUrl(String schemaUrl) {
    this.schemaUrl = schemaUrl;
    return this;
  }

  @Override
  public Meter build() {
    return meterProvider.getMeterShim(
        instrumentationScopeName, instrumentationScopeVersion, schemaUrl);
  }
}
