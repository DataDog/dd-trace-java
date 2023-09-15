package datadog.trace.instrumentation.tibcobw6;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;

public abstract class AbstractTibcoInstrumentation extends InstrumenterModule.Tracing {
  public AbstractTibcoInstrumentation() {
    super("tibco", "tibco_bw");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("com.tibco.pvm.api.PmWorkUnit", AgentSpan.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TibcoDecorator",
    };
  }
}
