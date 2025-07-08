package datadog.trace.instrumentation.tibcobw6;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.Strings;
import java.util.HashMap;
import java.util.Map;

public abstract class AbstractTibcoInstrumentation extends InstrumenterModule.Tracing {
  public AbstractTibcoInstrumentation() {
    super("tibco", "tibco_bw");
  }

  public AbstractTibcoInstrumentation(String... additionalNames) {
    super("tibco", Strings.concat(additionalNames, "tibco_bw"));
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> stores = new HashMap<>();
    stores.put("com.tibco.pvm.api.PmWorkUnit", AgentSpan.class.getName());
    stores.put(
        "com.tibco.bw.jms.shared.api.receive.JMSMessageCallBackHandler", String.class.getName());
    return stores;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TibcoDecorator", packageName + ".IgnoreHelper",
    };
  }
}
