package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.StructuredTaskScope;

public final class MultipleTasks implements TestCase {
  @WithSpan("parent")
  public void run() throws InterruptedException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(this::runnableTask1);
      scope.fork(this::callableTask2);
      scope.fork(this::runnableTask3);
      scope.join();
    }
  }

  @WithSpan("child1")
  void runnableTask1() {
    // Some basic computations here
  }

  @WithSpan("child2")
  Boolean callableTask2() {
    // Some basic computations here
    return true;
  }

  @WithSpan("child3")
  void runnableTask3() {
    // Some basic computations here
  }
}
