package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelMeterBuilder implements MeterBuilder {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelMeterBuilder.class);
  private final String instrumentationScopeName;
  private String schemaUrl;
  private String instrumentationVersion;

  public OtelMeterBuilder(String instrumentationScopeName) {
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  @ParametersAreNonnullByDefault
  public MeterBuilder setSchemaUrl(String schemaUrl) {
    this.schemaUrl = schemaUrl;
    return this;
  }

  @Override
  @ParametersAreNonnullByDefault
  public MeterBuilder setInstrumentationVersion(String instrumentationVersion) {
    this.instrumentationVersion = instrumentationVersion;
    return this;
  }

  @Override
  public Meter build() {
    return new OtelMeter(instrumentationScopeName, instrumentationVersion, schemaUrl);
  }
}
