package datadog.opentelemetry.shim.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelTracerBuilder implements TracerBuilder {
  private final OtelTracerProvider tracerProvider;
  private final String instrumentationScopeName;

  OtelTracerBuilder(OtelTracerProvider tracerProvider, String instrumentationScopeName) {
    this.tracerProvider = tracerProvider;
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  public TracerBuilder setInstrumentationVersion(String instrumentationScopeVersion) {
    // Not supported
    return this;
  }

  @Override
  public TracerBuilder setSchemaUrl(String schemaUrl) {
    // Not supported
    return this;
  }

  @Override
  public Tracer build() {
    return tracerProvider.getTracerShim(instrumentationScopeName);
  }
}
