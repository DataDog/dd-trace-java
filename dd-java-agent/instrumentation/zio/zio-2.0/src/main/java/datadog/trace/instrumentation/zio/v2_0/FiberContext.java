package datadog.trace.instrumentation.zio.v2_0;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextHandle;

public class FiberContext {
  private ContextHandle fiberContext;
  private final ContextContinuation continuation;

  private ContextHandle previousContext;

  public FiberContext() {
    Context initial = Context.current();
    // stop enclosing trace from finishing early
    this.continuation = initial.capture();
    this.fiberContext = initial.checkpoint();
  }

  public void onResume() {
    previousContext = fiberContext.swap();
  }

  public void onSuspend() {
    if (previousContext != null) {
      fiberContext = previousContext.swap();
      previousContext = null;
    }
  }

  public void onEnd() {
    if (continuation != null) {
      // release enclosing trace now the fiber has ended
      continuation.release();
    }
  }
}
