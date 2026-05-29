import static datadog.trace.agent.test.assertions.Matchers.matches;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import annotatedsample.RxJava3TracedMethods;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.junit.utils.config.WithConfig;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that {@code @WithSpan}-annotated methods returning RxJava 3 reactive types produce a
 * span whose duration spans until the reactive value completes, errors, or is cancelled.
 */
@WithConfig(key = "trace.otel.enabled", value = "true")
@WithConfig(key = "integration.opentelemetry-annotations-1.20.enabled", value = "true")
class RxJava3ResultExtensionTest extends AbstractInstrumentationTest {

  static Stream<Arguments> reactiveTypeArguments() {
    return Stream.of(
        arguments("Completable", "blockingAwait"),
        arguments("Maybe", "blockingGet"),
        arguments("Single", "blockingGet"),
        arguments("Observable", "blockingLast"),
        arguments("Flowable", "blockingLast"));
  }

  @ParameterizedTest(name = "WithSpan annotated async method ''{0}''")
  @MethodSource("reactiveTypeArguments")
  void withSpanAnnotatedAsyncMethod(String type, String operation) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    String method = "traceAsync" + type;
    Object asyncType = invokeFactory(method, latch, null);

    assertEquals(0, writer.size());

    latch.countDown();
    consume(asyncType, operation);

    assertTraces(
        trace(
            span()
                .root()
                .operationName(Pattern.compile(Pattern.quote("RxJava3TracedMethods." + method)))
                .resourceName(cs -> ("RxJava3TracedMethods." + method).contentEquals(cs))
                .tags(
                    defaultTags(),
                    tag(COMPONENT, matches("opentelemetry")),
                    tag(SPAN_KIND, matches("internal")))));
  }

  @ParameterizedTest(name = "WithSpan annotated async method failing ''{0}''")
  @MethodSource("reactiveTypeArguments")
  void withSpanAnnotatedAsyncMethodFailing(String type, String operation)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    IllegalStateException expected = new IllegalStateException("Test exception");
    String method = "traceAsyncFailing" + type;
    Object asyncType = invokeFactory(method, latch, expected);

    assertEquals(0, writer.size());

    latch.countDown();
    assertThrows(IllegalStateException.class, () -> consume(asyncType, operation));

    assertTraces(
        trace(
            span()
                .root()
                .operationName(Pattern.compile(Pattern.quote("RxJava3TracedMethods." + method)))
                .resourceName(cs -> ("RxJava3TracedMethods." + method).contentEquals(cs))
                .error()
                .tags(
                    defaultTags(),
                    tag(COMPONENT, matches("opentelemetry")),
                    tag(SPAN_KIND, matches("internal")),
                    error(IllegalStateException.class, "Test exception"))));
  }

  @ParameterizedTest(name = "WithSpan annotated async method cancelled ''{0}''")
  @MethodSource("reactiveTypeArguments")
  void withSpanAnnotatedAsyncMethodCancelled(String type, String operation)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    String method = "traceAsync" + type;
    Object asyncType = invokeFactory(method, latch, null);

    assertEquals(0, writer.size());

    latch.countDown();
    Disposable disposable = subscribe(asyncType);
    disposable.dispose();

    assertTraces(
        trace(
            span()
                .root()
                .operationName(Pattern.compile(Pattern.quote("RxJava3TracedMethods." + method)))
                .resourceName(cs -> ("RxJava3TracedMethods." + method).contentEquals(cs))
                .tags(
                    defaultTags(),
                    tag(COMPONENT, matches("opentelemetry")),
                    tag(SPAN_KIND, matches("internal")))));
  }

  private static Object invokeFactory(String method, CountDownLatch latch, Exception ex) {
    switch (method) {
      case "traceAsyncCompletable":
        return RxJava3TracedMethods.traceAsyncCompletable(latch);
      case "traceAsyncFailingCompletable":
        return RxJava3TracedMethods.traceAsyncFailingCompletable(latch, ex);
      case "traceAsyncMaybe":
        return RxJava3TracedMethods.traceAsyncMaybe(latch);
      case "traceAsyncFailingMaybe":
        return RxJava3TracedMethods.traceAsyncFailingMaybe(latch, ex);
      case "traceAsyncSingle":
        return RxJava3TracedMethods.traceAsyncSingle(latch);
      case "traceAsyncFailingSingle":
        return RxJava3TracedMethods.traceAsyncFailingSingle(latch, ex);
      case "traceAsyncObservable":
        return RxJava3TracedMethods.traceAsyncObservable(latch);
      case "traceAsyncFailingObservable":
        return RxJava3TracedMethods.traceAsyncFailingObservable(latch, ex);
      case "traceAsyncFlowable":
        return RxJava3TracedMethods.traceAsyncFlowable(latch);
      case "traceAsyncFailingFlowable":
        return RxJava3TracedMethods.traceAsyncFailingFlowable(latch, ex);
      default:
        throw new IllegalArgumentException("Unknown method: " + method);
    }
  }

  private static Object consume(Object reactive, String operation) {
    if (reactive instanceof Completable) {
      ((Completable) reactive).blockingAwait();
      return null;
    }
    if (reactive instanceof Maybe) {
      return ((Maybe<?>) reactive).blockingGet();
    }
    if (reactive instanceof Single) {
      return ((Single<?>) reactive).blockingGet();
    }
    if (reactive instanceof Observable) {
      return ((Observable<?>) reactive).blockingLast();
    }
    if (reactive instanceof Flowable) {
      return ((Flowable<?>) reactive).blockingLast();
    }
    throw new IllegalArgumentException(
        "Unsupported reactive type: " + reactive.getClass().getName());
  }

  private static Disposable subscribe(Object reactive) {
    if (reactive instanceof Completable) {
      return ((Completable) reactive).subscribe(() -> {}, t -> {});
    }
    if (reactive instanceof Maybe) {
      return ((Maybe<?>) reactive).subscribe(v -> {}, t -> {});
    }
    if (reactive instanceof Single) {
      return ((Single<?>) reactive).subscribe(v -> {}, t -> {});
    }
    if (reactive instanceof Observable) {
      return ((Observable<?>) reactive).subscribe(v -> {}, t -> {});
    }
    if (reactive instanceof Flowable) {
      return ((Flowable<?>) reactive).subscribe(v -> {}, t -> {});
    }
    throw new IllegalArgumentException(
        "Unsupported reactive type: " + reactive.getClass().getName());
  }
}
