package datadog.trace.instrumentation.mule4;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.Map;

public abstract class AbstractMuleInstrumentation extends InstrumenterModule.Tracing {
  public AbstractMuleInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> contextStore = new HashMap<>();
    contextStore.put("org.mule.runtime.api.event.EventContext", packageName + ".SpanState");
    contextStore.put(
        "org.mule.runtime.tracer.api.span.info.InitialSpanInfo",
        "org.mule.runtime.api.component.Component");
    return contextStore;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MuleDecorator",
      packageName + ".DDEventTracer",
      packageName + ".SpanState",
      packageName + ".NoopMuleSpan",
    };
  }
}
