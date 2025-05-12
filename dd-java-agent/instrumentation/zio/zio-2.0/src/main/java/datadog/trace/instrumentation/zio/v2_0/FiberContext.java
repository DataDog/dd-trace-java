package datadog.trace.instrumentation.zio.v2_0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;

public class FiberContext {
  private final ScopeState scopeState;
  private final AgentScope.Continuation continuation;

  private ScopeState oldScopeState;

  public FiberContext() {
    // copy scope stack to use for this fiber
    this.scopeState = AgentTracer.get().oldScopeState().copy();
    // stop enclosing trace from finishing early
    this.continuation = captureActiveSpan();
  }

  public void onResume() {
    oldScopeState = AgentTracer.get().oldScopeState();
    scopeState.activate(); // swap in the fiber's scope stack
  }

  public void onSuspend() {
    if (oldScopeState != null) {
      oldScopeState.activate(); // swap bock the original scope stack
      oldScopeState = null;
    }
  }

  public void onEnd() {
    if (continuation != null) {
      // release enclosing trace now the fiber has ended
      continuation.cancel();
    }
  }
}
