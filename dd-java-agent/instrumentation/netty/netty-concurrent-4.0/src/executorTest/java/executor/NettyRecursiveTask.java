package executor;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutorService;

public final class NettyRecursiveTask implements Runnable {
  public enum Dispatch {
    SUBMIT,
    EXECUTE,
    MIXED
  }

  private final ExecutorService executor;
  private final int maxDepth;
  private final int depth;
  private final Dispatch dispatch;

  public NettyRecursiveTask(ExecutorService executor, int maxDepth, int depth, Dispatch dispatch) {
    this.executor = executor;
    this.maxDepth = maxDepth;
    this.depth = depth;
    this.dispatch = dispatch;
  }

  @Override
  public void run() {
    if (depth == maxDepth) {
      return;
    }
    AgentSpan span = startSpan("test", String.valueOf(depth));
    try (ContextScope scope = activateSpan(span)) {
      NettyRecursiveTask next = new NettyRecursiveTask(executor, maxDepth, depth + 1, dispatch);
      if (dispatch == Dispatch.EXECUTE || (dispatch == Dispatch.MIXED && depth % 2 != 0)) {
        executor.execute(next);
      } else {
        executor.submit(next);
      }
    } finally {
      span.finish();
    }
  }
}
