package datadog.trace.instrumentation.zio.v2_0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;

public class FiberContext {
  private final ScopeState state;
  private AgentScope.Continuation continuation;
  private AgentScope scope;
  private ScopeState oldState;

  private FiberContext(ScopeState state) {
    this.state = state;
    this.scope = null;
    this.oldState = null;
    this.continuation = captureActiveSpan();
  }

  public static FiberContext create() {
    final ScopeState state = AgentTracer.get().newScopeState();
    return new FiberContext(state);
  }

  public void onEnd() {
    if (this.scope != null) {
      this.scope.close();
      this.scope = null;
    }
    if (continuation != null) {
      continuation.cancel();
      continuation = null;
    }

    if (this.oldState != null) {
      this.oldState.activate();
      this.oldState = null;
    }
  }

  public void onSuspend() {
    if (this.scope != null && continuation != null) {
      this.scope.close();
      this.scope = null;
    }
    if (this.oldState != null) {
      this.oldState.activate();
      this.oldState = null;
    }
  }

  public void onResume() {
    this.oldState = AgentTracer.get().oldScopeState();

    this.state.activate();

    if (this.continuation != null) {
      this.scope = continuation.activate();
      continuation = null;
    }
  }
}
