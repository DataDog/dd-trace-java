import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.Trace;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Fanout {

  private final Executor executor;
  private final int tasks;
  private final boolean traceChild;

  public Fanout(Executor executor, int tasks, boolean traceChild) {
    this.executor = executor;
    this.tasks = tasks;
    this.traceChild = traceChild;
  }

  public void execute() {
    try {
      Stream<CompletableFuture<?>> futures =
          IntStream.range(0, tasks)
              .mapToObj(
                  i ->
                      CompletableFuture.runAsync(
                          traceChild ? this::tracedWork : this::untracedWork, executor));
      // Wait for those threads to finish work
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  public void executeTwoLevels() {
    try {
      Stream<CompletableFuture<?>> futures =
          IntStream.range(0, tasks)
              .mapToObj(
                  i ->
                      CompletableFuture.runAsync(
                              traceChild ? this::tracedWork : this::untracedWork, executor)
                          .thenRunAsync(
                              traceChild ? this::tracedWork : this::untracedWork, executor));
      // Wait for those threads to finish work
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private void untracedWork() {
    assert null != activeSpan();
  }

  @Trace
  private void tracedWork() {
    assert null != activeSpan();
  }
}
