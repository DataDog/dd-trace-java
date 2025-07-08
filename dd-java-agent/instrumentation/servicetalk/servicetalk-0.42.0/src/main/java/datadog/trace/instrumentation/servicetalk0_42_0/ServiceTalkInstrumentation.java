package datadog.trace.instrumentation.servicetalk0_42_0;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
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
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      // This check prevents older instrumentation from being applied to ServiceTalk v0.42.56+
      new Reference.Builder("io.servicetalk.concurrent.api.DelegatingExecutor")
          // Removed in v0.42.56
          .withField(new String[0], 0, "delegate", "Lio/servicetalk/concurrent/api/Executor;")
          .build(),
    };
  }
}
