package datadog.opentelemetry.shim.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelTracerBuilder implements TracerBuilder {
  private final OtelTracerProvider tracerProvider;

  private final String instrumentationScopeName;
  @Nullable private String instrumentationScopeVersion;
  @Nullable private String schemaUrl;

  OtelTracerBuilder(OtelTracerProvider tracerProvider, String instrumentationScopeName) {
    this.tracerProvider = tracerProvider;
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  public TracerBuilder setInstrumentationVersion(String instrumentationScopeVersion) {
    this.instrumentationScopeVersion = instrumentationScopeVersion;
    return this;
  }

  @Override
  public TracerBuilder setSchemaUrl(String schemaUrl) {
    this.schemaUrl = schemaUrl;
    return this;
  }

  @Override
  public Tracer build() {
    return tracerProvider.getTracerShim(
        instrumentationScopeName, instrumentationScopeVersion, schemaUrl);
  }
}
