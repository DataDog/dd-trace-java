package testdog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.util.concurrent.StructuredTaskScope;
import org.junit.jupiter.api.Test;

/**
 * JDK specific tests for the fork-into-canceled-scope continuation leak, isolated from {@code
 * StructuredTaskScope25Test} because it uses the Java 25 {@code StructuredTaskScope.Joiner} API.
 */
@SuppressWarnings("preview")
public class StructuredTaskScopeCancelTest extends AbstractInstrumentationTest {
  @Test
  void testForkIntoCancelledScopeDoesNotLeakContinuation() throws Exception {
    var span = tracer.startSpan("test", "parent");
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open(new CancelOnForkJoiner<>())) {
        scope.fork(this::task);
        scope.join();
      }
    }
    span.finish();

    assertTraces(trace(span().root().operationName("parent")));
  }

  Void task() {
    tracer.startSpan("test", "child").finish();
    return null;
  }

  /** Cancels the scope as soon as the first subtask is forked. */
  static final class CancelOnForkJoiner<T> implements StructuredTaskScope.Joiner<T, Void> {
    @Override
    public boolean onFork(StructuredTaskScope.Subtask<? extends T> subtask) {
      return true; // Cancel the scope as soon as the first subtask is forked.
    }

    @Override
    public Void result() {
      return null;
    }
  }
}
