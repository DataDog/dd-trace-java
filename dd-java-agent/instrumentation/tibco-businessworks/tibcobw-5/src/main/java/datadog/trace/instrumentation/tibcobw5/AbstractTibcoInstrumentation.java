package datadog.trace.instrumentation.tibcobw5;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.Map;

public abstract class AbstractTibcoInstrumentation extends InstrumenterModule.Tracing {
  public AbstractTibcoInstrumentation() {
    super("tibco", "tibco_bw");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("com.tibco.pe.plugin.ProcessContext", Map.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TibcoDecorator",
      packageName + ".ActivityHelper",
      packageName + ".ActivityHelper$ActivityInfo",
      "com.tibco.pe.core.DDJobMate",
    };
  }
}
