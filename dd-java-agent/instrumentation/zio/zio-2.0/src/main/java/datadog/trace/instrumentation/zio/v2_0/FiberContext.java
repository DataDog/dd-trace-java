package datadog.trace.instrumentation.zio.v2_0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public class FiberContext {
  private final Context context;
  private AgentScope.Continuation continuation;
  private AgentScope scope;
  private Context oldContext;

  private FiberContext(Context context) {
    this.context = context;
    this.continuation = captureActiveSpan();
    this.scope = null;
    this.oldContext = null;
  }

  public static FiberContext create() {
    return new FiberContext(Context.root());
  }

  public void onEnd() {
    if (scope != null) {
      scope.close();
      scope = null;
    }
    if (continuation != null) {
      continuation.cancel();
      continuation = null;
    }
    if (oldContext != null) {
      oldContext.swap();
      oldContext = null;
    }
  }

  public void onSuspend() {
    if (scope != null && continuation != null) {
      scope.close();
      scope = null;
    }
    if (oldContext != null) {
      oldContext.swap();
      oldContext = null;
    }
  }

  public void onResume() {
    oldContext = context.swap();
    if (continuation != null) {
      scope = continuation.activate();
      continuation = null;
    }
  }
}
