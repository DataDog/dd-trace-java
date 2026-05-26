package datadog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import annotatedsample.RxJava3TracedMethods;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.junit.utils.config.WithConfig;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests that the RxJava 3 async result extension correctly completes spans for {@link
 * io.opentelemetry.instrumentation.annotations.WithSpan}-annotated methods that return RxJava 3
 * reactive types.
 *
 * <p>These tests verify that spans are not finished when the method returns, but only after the
 * reactive type signals completion (success, error, or cancellation).
 */
@WithConfig(key = "trace.otel.enabled", value = "true")
@WithConfig(key = "integration.opentelemetry-annotations-1.20.enabled", value = "true")
public class RxJava3ResultExtensionTest extends AbstractInstrumentationTest {

  // ---- Success path tests ----

  static Stream<Arguments> asyncSuccessProvider() {
    return Stream.of(
        Arguments.of("Completable", "traceAsyncCompletable", "blockingAwait"),
        Arguments.of("Maybe", "traceAsyncMaybe", "blockingGet"),
        Arguments.of("Single", "traceAsyncSingle", "blockingGet"),
        Arguments.of("Observable", "traceAsyncObservable", "blockingLast"),
        Arguments.of("Flowable", "traceAsyncFlowable", "blockingLast"));
  }

  @ParameterizedTest(name = "WithSpan annotated async method {0}")
  @MethodSource("asyncSuccessProvider")
  void withSpanAnnotatedAsyncMethodSuccess(String type, String method, String operation)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Object asyncType = invokeTracedMethod(method, latch);

    // Span should not be finished yet
    assertEquals(0, writer.size());

    latch.countDown();
    invokeBlockingOperation(asyncType, operation);

    assertTraces(
        trace(
            span()
                .root()
                .operationName("RxJava3TracedMethods.traceAsync" + type)
                .resourceName("RxJava3TracedMethods." + method)
                .tags(
                    defaultTags(),
                    tag(Tags.COMPONENT, is("opentelemetry")),
                    tag(Tags.SPAN_KIND, is("internal")))));
  }

  // ---- Failure path tests ----

  static Stream<Arguments> asyncFailureProvider() {
    return Stream.of(
        Arguments.of("Completable", "traceAsyncFailingCompletable", "blockingAwait"),
        Arguments.of("Maybe", "traceAsyncFailingMaybe", "blockingGet"),
        Arguments.of("Single", "traceAsyncFailingSingle", "blockingGet"),
        Arguments.of("Observable", "traceAsyncFailingObservable", "blockingLast"),
        Arguments.of("Flowable", "traceAsyncFailingFlowable", "blockingLast"));
  }

  @ParameterizedTest(name = "WithSpan annotated async method failing {0}")
  @MethodSource("asyncFailureProvider")
  void withSpanAnnotatedAsyncMethodFailure(String type, String method, String operation)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    IllegalStateException expectedException = new IllegalStateException("Test exception");
    Object asyncType = invokeFailingTracedMethod(method, latch, expectedException);

    // Span should not be finished yet
    assertEquals(0, writer.size());

    latch.countDown();
    assertThrows(IllegalStateException.class, () -> invokeBlockingOperation(asyncType, operation));

    assertTraces(
        trace(
            span()
                .root()
                .operationName("RxJava3TracedMethods." + method)
                .resourceName("RxJava3TracedMethods." + method)
                .error()
                .tags(
                    defaultTags(),
                    tag(Tags.COMPONENT, is("opentelemetry")),
                    tag(Tags.SPAN_KIND, is("internal")),
                    error(expectedException))));
  }

  // ---- Cancellation path tests ----

  static Stream<Arguments> asyncCancelProvider() {
    return Stream.of(
        Arguments.of("Completable", "traceAsyncCompletable"),
        Arguments.of("Maybe", "traceAsyncMaybe"),
        Arguments.of("Single", "traceAsyncSingle"),
        Arguments.of("Observable", "traceAsyncObservable"),
        Arguments.of("Flowable", "traceAsyncFlowable"));
  }

  @ParameterizedTest(name = "WithSpan annotated async method cancelled {0}")
  @MethodSource("asyncCancelProvider")
  void withSpanAnnotatedAsyncMethodCancelled(String type, String method) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Object asyncType = invokeTracedMethod(method, latch);

    // Span should not be finished yet
    assertEquals(0, writer.size());

    latch.countDown();
    disposeAsyncType(asyncType);

    assertTraces(
        trace(
            span()
                .root()
                .operationName("RxJava3TracedMethods.traceAsync" + type)
                .resourceName("RxJava3TracedMethods." + method)
                .tags(
                    defaultTags(),
                    tag(Tags.COMPONENT, is("opentelemetry")),
                    tag(Tags.SPAN_KIND, is("internal")))));
  }

  // ---- Helper methods ----

  private static Object invokeTracedMethod(String method, CountDownLatch latch) throws Exception {
    return RxJava3TracedMethods.class.getMethod(method, CountDownLatch.class).invoke(null, latch);
  }

  private static Object invokeFailingTracedMethod(
      String method, CountDownLatch latch, Exception exception) throws Exception {
    return RxJava3TracedMethods.class
        .getMethod(method, CountDownLatch.class, Exception.class)
        .invoke(null, latch, exception);
  }

  private static void invokeBlockingOperation(Object asyncType, String operation) throws Exception {
    try {
      asyncType.getClass().getMethod(operation).invoke(asyncType);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }

  private static void disposeAsyncType(Object asyncType) throws Exception {
    if (asyncType instanceof Completable) {
      ((Completable) asyncType).subscribe().dispose();
    } else if (asyncType instanceof Maybe) {
      ((Maybe<?>) asyncType).subscribe().dispose();
    } else if (asyncType instanceof Single) {
      ((Single<?>) asyncType).subscribe().dispose();
    } else if (asyncType instanceof Observable) {
      ((Observable<?>) asyncType).subscribe().dispose();
    } else if (asyncType instanceof Flowable) {
      ((Flowable<?>) asyncType).subscribe().dispose();
    }
  }
}
