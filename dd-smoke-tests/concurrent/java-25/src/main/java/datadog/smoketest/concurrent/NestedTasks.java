package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.StructuredTaskScope;

public final class NestedTasks implements TestCase {
  @WithSpan("parent")
  public void run() throws InterruptedException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(this::task1);
      scope.fork(this::task2);
      scope.join();
    }
  }

  @WithSpan("child1")
  void task1() {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(this::subTask1);
      scope.fork(this::subTask2);
      scope.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @WithSpan("child2")
  void task2() {
    // Some basic computations here
  }

  @WithSpan("great-child1-1")
  void subTask1() {
    // Some basic computations here
  }

  @WithSpan("great-child1-2")
  void subTask2() {
    // Some basic computations here
  }
}
