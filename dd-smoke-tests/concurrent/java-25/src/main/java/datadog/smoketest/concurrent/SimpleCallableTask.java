package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.StructuredTaskScope;

public final class SimpleCallableTask implements TestCase {
  @WithSpan("parent")
  public void run() throws InterruptedException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(this::doSomething);
      scope.join();
    }
  }

  @WithSpan("child")
  Boolean doSomething() {
    // Some basic computations here
    return true;
  }
}
