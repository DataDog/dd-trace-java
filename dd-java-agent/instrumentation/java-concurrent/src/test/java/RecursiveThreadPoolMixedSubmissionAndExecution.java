import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutorService;

public class RecursiveThreadPoolMixedSubmissionAndExecution implements Runnable {

  private final ExecutorService executor;

  private final int maxDepth;
  private final int depth;

  public RecursiveThreadPoolMixedSubmissionAndExecution(
      ExecutorService executor, int maxDepth, int depth) {
    this.executor = executor;
    this.maxDepth = maxDepth;
    this.depth = depth;
  }

  @Override
  public void run() {
    if (depth == maxDepth) {
      return;
    }
    AgentSpan span = startSpan(String.valueOf(depth));
    try (AgentScope scope = activateSpan(span)) {
      scope.setAsyncPropagation(true);
      if (depth % 2 == 0) {
        executor.submit(
            new RecursiveThreadPoolMixedSubmissionAndExecution(executor, maxDepth, depth + 1));
      } else {
        executor.execute(
            new RecursiveThreadPoolMixedSubmissionAndExecution(executor, maxDepth, depth + 1));
      }
    } finally {
      span.finish();
    }
  }
}
