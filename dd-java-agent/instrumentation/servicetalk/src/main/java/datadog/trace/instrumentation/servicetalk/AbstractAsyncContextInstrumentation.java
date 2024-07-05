package datadog.trace.instrumentation.servicetalk;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;

public abstract class AbstractAsyncContextInstrumentation extends InstrumenterModule.Tracing {

  public AbstractAsyncContextInstrumentation() {
    super("servicetalk", "servicetalk-concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.servicetalk.context.api.ContextMap", AgentSpan.class.getName());
  }
}
