package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import annotatedsample.RxJava3TracedMethods;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.junit.utils.config.WithConfig;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@WithConfig(key = "trace.otel.enabled", value = "true")
@WithConfig(key = "integration.opentelemetry-annotations-1.20.enabled", value = "true")
class RxJava3ResultExtensionTest extends AbstractInstrumentationTest {

  static final String EXCEPTION_MESSAGE = "Test exception";

  // The COMPONENT and SPAN_KIND tags are stored as UTF8BytesString, so we compare by string content
  // rather than using is("...") which would fail the asymmetric String#equals(UTF8BytesString)
  // check.
  static TagsMatcher otelComponent() {
    return tag(Tags.COMPONENT, validates(o -> "opentelemetry".equals(String.valueOf(o))));
  }

  static TagsMatcher internalSpanKind() {
    return tag(Tags.SPAN_KIND, validates(o -> Tags.SPAN_KIND_INTERNAL.equals(String.valueOf(o))));
  }

  // The operation and resource names are stored as UTF8BytesString, so we compare by string content
  // (CharSequence equality is asymmetric: String#equals(UTF8BytesString) is false).
  static SpanMatcher otelSpan(String name) {
    return span()
        .operationName(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name)))
        .resourceName((CharSequence cs) -> name.contentEquals(cs));
  }

  /**
   * The five reactive types exercised by the test, with their type-specific terminal operations.
   */
  enum ReactiveType {
    COMPLETABLE("Completable"),
    MAYBE("Maybe"),
    SINGLE("Single"),
    OBSERVABLE("Observable"),
    FLOWABLE("Flowable");

    final String type;

    ReactiveType(String type) {
      this.type = type;
    }

    /** Runs the blocking terminal operation that drives the async result to completion. */
    void runTerminal(Object asyncType) {
      switch (this) {
        case COMPLETABLE:
          ((Completable) asyncType).blockingAwait();
          break;
        case MAYBE:
          ((Maybe<?>) asyncType).blockingGet();
          break;
        case SINGLE:
          ((Single<?>) asyncType).blockingGet();
          break;
        case OBSERVABLE:
          ((Observable<?>) asyncType).blockingLast();
          break;
        case FLOWABLE:
          ((Flowable<?>) asyncType).blockingLast();
          break;
        default:
          throw new IllegalStateException("Unknown type: " + this);
      }
    }

    /** Subscribes and immediately disposes (cancels) the async result. */
    void subscribeAndDispose(Object asyncType) {
      switch (this) {
        case COMPLETABLE:
          ((Completable) asyncType).subscribe().dispose();
          break;
        case MAYBE:
          ((Maybe<?>) asyncType).subscribe().dispose();
          break;
        case SINGLE:
          ((Single<?>) asyncType).subscribe().dispose();
          break;
        case OBSERVABLE:
          ((Observable<?>) asyncType).subscribe().dispose();
          break;
        case FLOWABLE:
          ((Flowable<?>) asyncType).subscribe().dispose();
          break;
        default:
          throw new IllegalStateException("Unknown type: " + this);
      }
    }

    Object traceAsync(CountDownLatch latch) {
      switch (this) {
        case COMPLETABLE:
          return RxJava3TracedMethods.traceAsyncCompletable(latch);
        case MAYBE:
          return RxJava3TracedMethods.traceAsyncMaybe(latch);
        case SINGLE:
          return RxJava3TracedMethods.traceAsyncSingle(latch);
        case OBSERVABLE:
          return RxJava3TracedMethods.traceAsyncObservable(latch);
        case FLOWABLE:
          return RxJava3TracedMethods.traceAsyncFlowable(latch);
        default:
          throw new IllegalStateException("Unknown type: " + this);
      }
    }

    Object traceAsyncFailing(CountDownLatch latch, Exception exception) {
      switch (this) {
        case COMPLETABLE:
          return RxJava3TracedMethods.traceAsyncFailingCompletable(latch, exception);
        case MAYBE:
          return RxJava3TracedMethods.traceAsyncFailingMaybe(latch, exception);
        case SINGLE:
          return RxJava3TracedMethods.traceAsyncFailingSingle(latch, exception);
        case OBSERVABLE:
          return RxJava3TracedMethods.traceAsyncFailingObservable(latch, exception);
        case FLOWABLE:
          return RxJava3TracedMethods.traceAsyncFailingFlowable(latch, exception);
        default:
          throw new IllegalStateException("Unknown type: " + this);
      }
    }
  }

  @ParameterizedTest(name = "test WithSpan annotated async method {0}")
  @EnumSource(ReactiveType.class)
  void success(ReactiveType type) {
    CountDownLatch latch = new CountDownLatch(1);
    Object asyncType = type.traceAsync(latch);

    // The span must not be finished before the async result completes.
    assertEquals(0, writer.size());

    latch.countDown();
    type.runTerminal(asyncType);

    String method = "traceAsync" + type.type;
    assertTraces(
        trace(
            otelSpan("RxJava3TracedMethods." + method)
                .tags(defaultTags(), otelComponent(), internalSpanKind())));
  }

  @ParameterizedTest(name = "test WithSpan annotated async method failing {0}")
  @EnumSource(ReactiveType.class)
  void failing(ReactiveType type) {
    CountDownLatch latch = new CountDownLatch(1);
    IllegalStateException expectedException = new IllegalStateException(EXCEPTION_MESSAGE);
    Object asyncType = type.traceAsyncFailing(latch, expectedException);

    assertEquals(0, writer.size());

    latch.countDown();
    assertThrows(IllegalStateException.class, () -> type.runTerminal(asyncType));

    String method = "traceAsyncFailing" + type.type;
    assertTraces(
        trace(
            otelSpan("RxJava3TracedMethods." + method)
                .error()
                .tags(
                    defaultTags(),
                    otelComponent(),
                    internalSpanKind(),
                    error(IllegalStateException.class, EXCEPTION_MESSAGE))));
  }

  @ParameterizedTest(name = "test WithSpan annotated async method cancelled {0}")
  @EnumSource(ReactiveType.class)
  void cancelled(ReactiveType type) {
    CountDownLatch latch = new CountDownLatch(1);
    Object asyncType = type.traceAsync(latch);

    assertEquals(0, writer.size());

    latch.countDown();
    type.subscribeAndDispose(asyncType);

    String method = "traceAsync" + type.type;
    assertTraces(
        trace(
            otelSpan("RxJava3TracedMethods." + method)
                .tags(defaultTags(), otelComponent(), internalSpanKind())));
  }
}
