package trace.instrumentation.crac;

import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.crac.Context;
import org.crac.Resource;

public final class TracerResource implements Resource {

  @Override
  public void beforeCheckpoint(Context<? extends Resource> context) {
    ((AgentTracer.TracerAPI) GlobalTracer.get()).pause();
  }

  @Override
  public void afterRestore(Context<? extends Resource> context) {}
}
