package datadog.trace.instrumentation.servicetalk0_42_0;

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

  @Override
  protected boolean defaultEnabled() {
    // Instrumentation for ServiceTalk prior to 0.42.56 is disabled by default to avoid the missing
    // private field "saved" error.
    // This can't be addressed by a muzzle check because there is no decent public change between
    // 0.42.55 and 0.42.56.
    // For versions prior to 0.42.56, this instrumentation must be explicitly enabled with
    // 'DD_INTEGRATION_SERVICETALK_ENABLED=true'.
    return false;
  }
}
