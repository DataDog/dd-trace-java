package datadog.opentelemetry.tooling;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;

/**
 * Replaces OpenTelemetry's {@code InstrumentationModule} when mapping extensions.
 *
 * <p>Original instrumentation names and aliases are prefixed with {@literal "otel."}.
 */
public abstract class OtelInstrumenterModule extends InstrumenterModule.Tracing {

  public OtelInstrumenterModule(String instrumentationName, String... additionalNames) {
    super(namespace(instrumentationName), namespace(additionalNames));
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isTraceOtelEnabled() && super.defaultEnabled();
  }

  private static String namespace(String name) {
    return "otel." + name;
  }

  private static String[] namespace(String[] names) {
    String[] namespaced = new String[names.length];
    for (int i = 0; i < names.length; i++) {
      namespaced[i] = namespace(names[i]);
    }
    return namespaced;
  }
}
