package datadog.trace.core;

import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
import datadog.trace.core.scopemanager.ScopeStackCoroutineContext;
import kotlin.coroutines.CoroutineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScopeStackCoroutineContextHelper {

  private static final NoOpContextElement NO_OP_CONTEXT_ELEMENT = new NoOpContextElement();
  private static final Logger logger =
      LoggerFactory.getLogger(ScopeStackCoroutineContextHelper.class);

  public static CoroutineContext addScopeStackContext(final CoroutineContext other) {
    final AgentTracer.TracerAPI agentTracer = AgentTracer.get();
    final AgentScopeManager agentScopeManager =
        agentTracer instanceof CoreTracer ? ((CoreTracer) agentTracer).scopeManager : null;

    if (agentScopeManager instanceof ContinuableScopeManager) {
      return other.plus(
          new ScopeStackCoroutineContext((ContinuableScopeManager) agentScopeManager));
    }

    logger.warn(
        "Unexpected Tracer or Scope Manager implementation. Tracer[expected={}, got={}], ScopeManager[expected={}, got={}]",
        CoreTracer.class.getName(),
        agentTracer.getClass().getName(),
        ContinuableScopeManager.class.getName(),
        agentScopeManager != null ? agentScopeManager.getClass() : "null");

    return NO_OP_CONTEXT_ELEMENT;
  }
}
