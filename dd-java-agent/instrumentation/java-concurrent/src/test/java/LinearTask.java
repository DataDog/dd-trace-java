import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.RecursiveTask;

public class LinearTask extends RecursiveTask<Integer> {
  private final int depth;
  private final int parent;

  public LinearTask(int depth) {
    this(0, depth);
  }

  private LinearTask(int parent, int depth) {
    this.parent = parent;
    this.depth = depth;
  }

  @Override
  protected Integer compute() {
    try {
      // introduces delay to encourage parallelism
      // which will expose problems with context propagation
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (parent == depth) {
      return parent;
    } else {
      int next = parent + 1;
      AgentSpan span = startSpan(Integer.toString(next));
      try (AgentScope scope = activateSpan(span)) {
        // shouldn't be necessary with a decent API
        scope.setAsyncPropagation(true);
        LinearTask child = new LinearTask(next, depth);
        return child.fork().join();
      } finally {
        span.finish();
      }
    }
  }
}
