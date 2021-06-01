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
      Runnable task = traceChild ? this::tracedWork : this::untracedWork;
      Stream<CompletableFuture<?>> futures =
          IntStream.range(0, tasks).mapToObj(i -> CompletableFuture.runAsync(task, executor));
      // Wait for those threads to finish work
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private void untracedWork() {
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Trace
  private void tracedWork() {
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
