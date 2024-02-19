package datadog.trace.instrumentation.automatic;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;

public abstract class OtelAutomaticInstrumentation extends InstrumenterModule.Tracing {
  public OtelAutomaticInstrumentation(String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  protected boolean defaultEnabled() {
    // NOTE: This won't work with GraalVM Native Image
    // We might need to include it within InstrumenterConfig for Native Image support
    return Config.get()
        .configProvider()
        .isEnabled(this.names(), "otel.instrumentation.", ".enabled", true);
  }
}
