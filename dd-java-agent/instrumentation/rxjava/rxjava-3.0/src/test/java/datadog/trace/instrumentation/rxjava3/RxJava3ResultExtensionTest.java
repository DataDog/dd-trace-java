package datadog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import annotatedsample.RxJava3TracedMethods;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.Matchers;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for RxJava 3 async result extension with {@code @WithSpan} annotated methods.
 *
 * <p>Verifies that when a method annotated with {@code @WithSpan} returns an RxJava 3 reactive
 * type, the span is completed when the reactive type completes (success, error, or cancellation),
 * not when the method returns.
 */
@WithConfig(key = "dd.trace.otel.enabled", value = "true")
@WithConfig(key = "dd.integration.opentelemetry-annotations-1.20.enabled", value = "true")
class RxJava3ResultExtensionTest extends AbstractInstrumentationTest {

  // ---------------------------------------------------------------------------
  // Successful async completion: span finishes when reactive type completes
  // ---------------------------------------------------------------------------

  static Stream<Arguments> asyncSuccessTestCases() {
    return Stream.of(
        Arguments.of("Completable", "blockingAwait"),
        Arguments.of("Maybe", "blockingGet"),
        Arguments.of("Single", "blockingGet"),
        Arguments.of("Observable", "blockingLast"),
        Arguments.of("Flowable", "blockingLast"));
  }

  @ParameterizedTest(name = "test WithSpan annotated async method {0}")
  @MethodSource("asyncSuccessTestCases")
  void asyncSuccessTest(String type, String operation) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    String methodName = "traceAsync" + type;
    Object asyncType = invokeTracedMethod(methodName, latch);

    // No spans should be written yet — the reactive type has not completed
    assertEquals(0, writer.size());

    latch.countDown();
    invokeBlockingOp(asyncType, operation);

    String expectedOpName = "RxJava3TracedMethods." + methodName;
    String expectedResName = "RxJava3TracedMethods." + methodName;
    assertTraces(
        trace(
            SpanMatcher.span()
                .operationName(Pattern.compile(Pattern.quote(expectedOpName)))
                .resourceName(Pattern.compile(Pattern.quote(expectedResName)))
                .root()
                .tags(
                    defaultTags(),
                    TagsMatcher.tag(Tags.COMPONENT, Matchers.matches("opentelemetry")),
                    TagsMatcher.tag(Tags.SPAN_KIND, Matchers.matches("internal")))));
  }

  // ---------------------------------------------------------------------------
  // Failing async completion: span finishes with error when reactive type errors
  // ---------------------------------------------------------------------------

  static Stream<Arguments> asyncFailureTestCases() {
    return Stream.of(
        Arguments.of("Completable", "blockingAwait"),
        Arguments.of("Maybe", "blockingGet"),
        Arguments.of("Single", "blockingGet"),
        Arguments.of("Observable", "blockingLast"),
        Arguments.of("Flowable", "blockingLast"));
  }

  @ParameterizedTest(name = "test WithSpan annotated async method failing {0}")
  @MethodSource("asyncFailureTestCases")
  void asyncFailureTest(String type, String operation) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    IllegalStateException expectedException = new IllegalStateException("Test exception");
    String methodName = "traceAsyncFailing" + type;
    Object asyncType = invokeTracedMethodFailing(methodName, latch, expectedException);

    assertEquals(0, writer.size());

    latch.countDown();
    assertThrows(RuntimeException.class, () -> invokeBlockingOp(asyncType, operation));

    String expectedName = "RxJava3TracedMethods." + methodName;
    assertTraces(
        trace(
            SpanMatcher.span()
                .operationName(Pattern.compile(Pattern.quote(expectedName)))
                .resourceName(Pattern.compile(Pattern.quote(expectedName)))
                .root()
                .error()
                .tags(
                    defaultTags(),
                    TagsMatcher.tag(Tags.COMPONENT, Matchers.matches("opentelemetry")),
                    TagsMatcher.tag(Tags.SPAN_KIND, Matchers.matches("internal")),
                    TagsMatcher.error(expectedException))));
  }

  // ---------------------------------------------------------------------------
  // Cancelled async: span finishes when subscriber disposes
  // ---------------------------------------------------------------------------

  static Stream<Arguments> asyncCancelTestCases() {
    return Stream.of(
        Arguments.of("Completable"),
        Arguments.of("Maybe"),
        Arguments.of("Single"),
        Arguments.of("Observable"),
        Arguments.of("Flowable"));
  }

  @ParameterizedTest(name = "test WithSpan annotated async method cancelled {0}")
  @MethodSource("asyncCancelTestCases")
  void asyncCancelTest(String type) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    String methodName = "traceAsync" + type;
    Object asyncType = invokeTracedMethod(methodName, latch);

    assertEquals(0, writer.size());

    latch.countDown();
    invokeSubscribeAndDispose(asyncType);

    String expectedOpName = "RxJava3TracedMethods." + methodName;
    String expectedResName = "RxJava3TracedMethods." + methodName;
    assertTraces(
        trace(
            SpanMatcher.span()
                .operationName(Pattern.compile(Pattern.quote(expectedOpName)))
                .resourceName(Pattern.compile(Pattern.quote(expectedResName)))
                .root()
                .tags(
                    defaultTags(),
                    TagsMatcher.tag(Tags.COMPONENT, Matchers.matches("opentelemetry")),
                    TagsMatcher.tag(Tags.SPAN_KIND, Matchers.matches("internal")))));
  }

  // ---------------------------------------------------------------------------
  // Reflective helper methods to invoke traced methods by name
  // ---------------------------------------------------------------------------

  private static Object invokeTracedMethod(String methodName, CountDownLatch latch)
      throws Exception {
    return RxJava3TracedMethods.class
        .getMethod(methodName, CountDownLatch.class)
        .invoke(null, latch);
  }

  private static Object invokeTracedMethodFailing(
      String methodName, CountDownLatch latch, Exception exception) throws Exception {
    return RxJava3TracedMethods.class
        .getMethod(methodName, CountDownLatch.class, Exception.class)
        .invoke(null, latch, exception);
  }

  private static void invokeBlockingOp(Object asyncType, String operation) throws Exception {
    try {
      asyncType.getClass().getMethod(operation).invoke(asyncType);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof Error) {
        throw (Error) cause;
      } else {
        throw new RuntimeException(cause);
      }
    }
  }

  private static void invokeSubscribeAndDispose(Object asyncType) throws Exception {
    Object disposable = asyncType.getClass().getMethod("subscribe").invoke(asyncType);
    disposable.getClass().getMethod("dispose").invoke(disposable);
  }
}
