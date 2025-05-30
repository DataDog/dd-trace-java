package datadog.trace.instrumentation.servicetalk0_42_56;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.servicetalk.concurrent.api.CapturedContext;
import io.servicetalk.concurrent.api.CapturedContextProvider;
import io.servicetalk.concurrent.api.Scope;
import io.servicetalk.context.api.ContextMap;

public final class DatadogCapturedContextProvider implements CapturedContextProvider {
  @Override
  public CapturedContext captureContext(CapturedContext underlying) {
    return captureContextCopy(underlying);
  }

  @Override
  public CapturedContext captureContextCopy(CapturedContext underlying) {
    return new WithDatadogCapturedContext(AgentTracer.activeSpan(), underlying);
  }

  private static final class WithDatadogCapturedContext implements CapturedContext {
    private final AgentSpan agentSpan;
    private final CapturedContext underlying;

    public WithDatadogCapturedContext(AgentSpan agentSpan, CapturedContext underlying) {
      this.agentSpan = agentSpan;
      this.underlying = underlying;
    }

    @Override
    public ContextMap captured() {
      return underlying.captured();
    }

    @Override
    public Scope attachContext() {
      Scope stScope = underlying.attachContext();
      AgentScope ddScope = AgentTracer.activateSpan(agentSpan);
      return () -> {
        ddScope.close();
        stScope.close();
      };
    }
  }
}
