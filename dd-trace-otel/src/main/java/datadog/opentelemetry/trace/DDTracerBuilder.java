package datadog.opentelemetry.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
class DDTracerBuilder implements TracerBuilder {
  private final String instrumentationScopeName;

  public DDTracerBuilder(String instrumentationScopeName) {
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  public TracerBuilder setSchemaUrl(String schemaUrl) {
    return this;
  }

  @Override
  public TracerBuilder setInstrumentationVersion(String instrumentationScopeVersion) {
    return this;
  }

  @Override
  public Tracer build() {
    return new DDTracer(this.instrumentationScopeName);
  }
}
