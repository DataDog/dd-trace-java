package datadog.trace.instrumentation.zio.v2_0;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;

public class FiberContext {

  private final ScopeState state;
  private AgentScope.Continuation continuation;
  private AgentScope scope;
  private ScopeState oldState;

  private FiberContext(ScopeState state, AgentScope.Continuation continuation) {
    this.state = state;
    this.continuation = continuation;
    this.scope = null;
    this.oldState = null;
  }

  public static FiberContext create() {
    final ScopeState state = AgentTracer.get().newScopeState();
    final AgentScope scope = AgentTracer.get().activeScope();
    final AgentScope.Continuation continuation;

    if (scope != null && scope.isAsyncPropagating()) {
      continuation = scope.capture();
    } else {
      continuation = null;
    }

    return new FiberContext(state, continuation);
  }

  public void onEnd() {
    if (this.scope != null) {
      this.scope.close();
      this.scope = null;
    }

    if (this.continuation != null) {
      this.continuation.cancel();
      this.continuation = null;
    }

    if (this.oldState != null) {
      this.oldState.activate();
      this.oldState = null;
    }
  }

  public void onSuspend() {
    if (this.oldState != null) {
      this.oldState.activate();
      this.oldState = null;
    }
  }

  public void onResume() {
    this.oldState = AgentTracer.get().newScopeState();
    this.oldState.fetchFromActive();

    this.state.activate();

    if (this.continuation != null && this.scope == null) {
      this.scope = this.continuation.activate();
      this.continuation = null;
    }
  }
}
