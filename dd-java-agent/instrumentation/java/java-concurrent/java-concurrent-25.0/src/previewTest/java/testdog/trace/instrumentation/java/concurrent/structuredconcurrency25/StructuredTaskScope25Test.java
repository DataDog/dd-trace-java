package testdog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static java.util.Comparator.comparing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanId;
import datadog.trace.core.DDSpan;
import java.util.Comparator;
import java.util.concurrent.StructuredTaskScope;
import org.junit.jupiter.api.Test;

@SuppressWarnings("preview")
public class StructuredTaskScope25Test extends AbstractInstrumentationTest {
  /** Tests the structured task scope with a single task. */
  @Test
  void testSingleTaskTracking() throws Exception {
    var span = tracer.startSpan("test", "parent");
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open()) {
        scope.fork(() -> task("child"));
        scope.join();
      }
    }
    span.finish();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("child")));
  }

  /**
   * Tests the structured task scope with multiple tasks. Here is the expected task/span structure:
   *
   * <pre>
   *   parent
   *   |-- child1
   *   |-- child2
   *   \-- child3
   * </pre>
   */
  @Test
  void testMultipleTasksTracking() throws Exception {
    var span = tracer.startSpan("test", "parent");
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open()) {
        scope.fork(() -> task("child1"));
        scope.fork(() -> task("child2"));
        scope.fork(() -> task("child3"));
        scope.join();
      }
    }
    span.finish();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfIndex(0).operationName("child1"),
            span().childOfIndex(0).operationName("child2"),
            span().childOfIndex(0).operationName("child3")));
  }

  /**
   * Tests the structured task scope with multiple nested tasks. Here is the expected task/span
   * structure:
   *
   * <pre>
   *   parent
   *   |-- child1
   *   |   |-- grandchild1
   *   |   \-- grandchild2
   *   \-- child2
   * </pre>
   */
  @Test
  void testNestedTasksTracking() throws Exception {
    var span = tracer.startSpan("test", "parent");
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open()) {
        scope.fork(() -> nestedChildren("child1"));
        scope.fork(() -> task("child2"));
        scope.join();
      }
    }
    span.finish();

    assertTraces(
        trace(
            options -> options.sorter(SORT_BY_OPERATION_NAME),
            span().childOfIndex(4).operationName("child1"),
            span().childOfIndex(4).operationName("child2"),
            span().childOfIndex(0).operationName("grandchild1"),
            span().childOfIndex(0).operationName("grandchild2"),
            span().root().operationName("parent")));
  }

  Comparator<DDSpan> SORT_BY_OPERATION_NAME =
      comparing(DDSpan::getOperationName, comparing(CharSequence::toString));

  Void nestedChildren(String name) throws Exception {
    var span = tracer.startSpan("test", name);
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open()) {
        scope.fork(() -> task("grandchild1"));
        scope.fork(() -> task("grandchild2"));
        scope.join();
      }
    }
    span.finish();
    return null;
  }

  @Test
  void testTasksInheritContext() throws Exception {
    var span = tracer.startSpan("test", "parent");
    var expectedSpanId = DDSpanId.toString(span.getSpanId());
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open()) {
        for (int i = 0; i < 5; i++) {
          scope.fork(() -> assertEquals(expectedSpanId, tracer.getSpanId()));
        }
        scope.join();
      }
    }
    span.finish();

    assertTraces(trace(span().root().operationName("parent")));
  }

  @Test
  void testFailingTaskDoesNotLeakContinuation() {
    var span = tracer.startSpan("test", "parent");
    var parentSpanId = DDSpanId.toString(span.getSpanId());
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open()) {
        scope.fork(this::failingTask);
        assertThrows(Exception.class, scope::join);
      }
      assertEquals(
          parentSpanId, tracer.getSpanId(), "parent context should be restored after the scope");
    }
    span.finish();

    assertTraces(trace(span().root().operationName("parent")));
  }

  Void task(String name) {
    tracer.startSpan("test", name).finish();
    return null;
  }

  Void failingTask() {
    throw new IllegalStateException("failing");
  }
}
