package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
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

class RxJava3InteropTest extends AbstractInstrumentationTest {

  // The component tag is stored as a UTF8BytesString, so compare by string content.
  static TagsMatcher componentTrace() {
    return tag(Tags.COMPONENT, validates(o -> "trace".equals(String.valueOf(o))));
  }

  static class Worker {
    static int child(int i) {
      return childTraced(i);
    }

    @Trace(operationName = "child", resourceName = "child")
    static int childTraced(int i) {
      return i + 1;
    }

    @Trace(operationName = "interop-parent", resourceName = "interop-parent")
    static <T> T runUnderParent(Supplier<T> work) {
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
                .childOfIndex(0)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags())));
  }

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
                .childOfIndex(0)
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
                .childOfIndex(0)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags())));
  }

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
                .childOfIndex(0)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags()),
            span()
                .childOfIndex(0)
                .operationName("child")
                .resourceName("child")
                .tags(componentTrace(), defaultTags())));
  }
}
