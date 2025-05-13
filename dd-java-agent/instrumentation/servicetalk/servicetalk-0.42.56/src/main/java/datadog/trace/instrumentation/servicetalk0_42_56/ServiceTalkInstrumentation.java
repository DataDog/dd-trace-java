package datadog.trace.instrumentation.servicetalk0_42_56;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;

public abstract class ServiceTalkInstrumentation extends InstrumenterModule.Tracing {

  public ServiceTalkInstrumentation() {
    super("servicetalk", "servicetalk-concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.servicetalk.context.api.ContextMap", AgentSpan.class.getName());
  }
}
