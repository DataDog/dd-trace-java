package datadog.trace.instrumentation.zio.v2_0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public class FiberContext {
  private Context context;
  private final AgentScope.Continuation continuation;

  private Context originalContext;

  public FiberContext() {
    // record context to use for this coroutine
    this.context = Context.current();
    // stop enclosing trace from finishing early
    this.continuation = captureActiveSpan();
  }

  public void onResume() {
    originalContext = context.swap();
  }

  public void onSuspend() {
    if (originalContext != null) {
      context = originalContext.swap();
      originalContext = null;
    }
  }

  public void onEnd() {
    if (continuation != null) {
      // release enclosing trace now the fiber has ended
      continuation.cancel();
    }
  }
}
