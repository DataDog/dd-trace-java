package datadog.opentelemetry.shim.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
class OtelTracerBuilder implements TracerBuilder {
  private final String instrumentationScopeName;

  public OtelTracerBuilder(String instrumentationScopeName) {
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  public TracerBuilder setSchemaUrl(String schemaUrl) {
    // Not supported
    return this;
  }

  @Override
  public TracerBuilder setInstrumentationVersion(String instrumentationScopeVersion) {
    // Not supported
    return this;
  }

  @Override
  public Tracer build() {
    return new OtelTracer(this.instrumentationScopeName);
  }
}
