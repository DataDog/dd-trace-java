package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// NOTE: This test lives in the `testdog` package (not `datadog`) on purpose: the agent ignores
// `datadog.*` classes for instrumentation, so `@Trace`-annotated methods declared under `datadog.*`
// would never be instrumented. See RxJava3Test for the same convention.
//
// PURPOSE: investigate whether a Datadog trace context propagates through RxJava 3's Java 8 interop
// factory methods (fromCompletionStage / fromOptional / fromStream). There is no dedicated reactive
// instrumentation for these bridges; any propagation must come from the agent's
// concurrent/executor instrumentation. Each test asserts the ACTUAL observed behavior.
class RxJava3InteropTest extends AbstractInstrumentationTest {

  static {
    // Async completion / scheduler hops can finish child spans after the local root is written,
    // tripping strict trace write ordering checks. Mirror RxJava3Test.
    testConfig.strictTraceWrites(false);
  }

  // The component tag is stored as a UTF8BytesString, so compare by string content.
  static TagsMatcher componentTrace() {
    return tag(Tags.COMPONENT, validates(o -> "trace".equals(String.valueOf(o))));
  }

  static class Worker {
    static long parentId;

    static int child(int i) {
      return childTraced(i);
    }

    @Trace(operationName = "child", resourceName = "child")
    static int childTraced(int i) {
      return i + 1;
    }

    @Trace(operationName = "interop-parent", resourceName = "interop-parent")
    static <T> T runUnderParent(Supplier<T> work) {
      parentId = activeSpan().getSpanId();
      return work.get();
    }
  }

  @Test
  void fromCompletionStageSync() {
    Integer result =
        Worker.runUnderParent(
            () ->
                Single.fromCompletionStage(CompletableFuture.completedFuture(1))
                    .map(Worker::child)
                    .blockingGet());
    assertEquals(2, result);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("interop-parent").resourceName("interop-parent"),
            span()
                .childOf(Worker.parentId)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags())));
  }

  /**
   * FINDING: context propagates even when the CompletableFuture is completed on another thread.
   * There is no rxjava3 instrumentation for fromCompletionStage; propagation comes from the agent's
   * concurrent/executor instrumentation, which carries the active context across the ForkJoinPool
   * used by supplyAsync. blockingGet() runs the map() on the calling thread, where the
   * interop-parent scope is still active, so the child span is a direct child of interop-parent.
   */
  @Test
  void fromCompletionStageAsync() {
    Integer result =
        Worker.runUnderParent(
            () ->
                Single.fromCompletionStage(CompletableFuture.supplyAsync(() -> 1))
                    .map(Worker::child)
                    .blockingGet());
    assertEquals(2, result);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("interop-parent").resourceName("interop-parent"),
            span()
                .childOf(Worker.parentId)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags())));
  }

  @Test
  void fromOptional() {
    Integer result =
        Worker.runUnderParent(
            () -> Maybe.fromOptional(Optional.of(1)).map(Worker::child).blockingGet());
    assertEquals(2, result);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("interop-parent").resourceName("interop-parent"),
            span()
                .childOf(Worker.parentId)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags())));
  }

  /**
   * FINDING: fromStream(2 elements) emits one child span per element (2 spans here), each a direct
   * child of interop-parent. The map() runs synchronously on the subscribing thread under the
   * active interop-parent scope, so no async/concurrent instrumentation is involved.
   */
  @Test
  void fromStream() {
    List<Integer> result =
        Worker.runUnderParent(
            () -> Flowable.fromStream(Stream.of(1, 2)).map(Worker::child).toList().blockingGet());
    assertEquals(2, result.size());
    assertEquals(2, result.get(0));
    assertEquals(3, result.get(1));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("interop-parent").resourceName("interop-parent"),
            span()
                .childOf(Worker.parentId)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags()),
            span()
                .childOf(Worker.parentId)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags())));
  }
}
