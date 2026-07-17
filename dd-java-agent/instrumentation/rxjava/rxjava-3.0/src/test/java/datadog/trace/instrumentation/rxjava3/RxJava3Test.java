package datadog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Tests that RxJava 3 context propagation correctly bridges parent-child span relationships across
 * reactive operator chains. The instrumentation creates no spans of its own — it ensures that spans
 * created inside reactive callbacks are correctly parented to the span active at subscription time.
 */
class RxJava3Test extends AbstractInstrumentationTest {

  static final String EXCEPTION_MESSAGE = "test exception";

  // --- Basic publisher tests: spans in map operators are children of publisher-parent ---

  @Test
  void basicMaybeContextPropagation() {
    int result = assemblePublisherUnderTrace(() -> Maybe.just(1).map(RxJava3Test::addOneFunc));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  @Test
  void twoOperationsMaybeContextPropagation() {
    int result =
        assemblePublisherUnderTrace(
            () -> Maybe.just(2).map(RxJava3Test::addOneFunc).map(RxJava3Test::addOneFunc));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  @Test
  void delayedMaybeContextPropagation() {
    int result =
        assemblePublisherUnderTrace(
            () -> Maybe.just(3).delay(100, TimeUnit.MILLISECONDS).map(RxJava3Test::addOneFunc));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  @Test
  void basicFlowableContextPropagation() {
    Object result =
        assemblePublisherUnderTrace(
            () -> Flowable.fromIterable(Arrays.asList(5, 6)).map(RxJava3Test::addOneFunc));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  @Test
  void basicSingleContextPropagation() {
    Object result =
        assemblePublisherUnderTrace(() -> Single.just(1).map(RxJava3Test::addOneFunc).toMaybe());

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  @Test
  void basicObservableContextPropagation() {
    Object result =
        assemblePublisherUnderTrace(
            () -> Observable.just(1).map(RxJava3Test::addOneFunc).firstElement());

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  // --- Error tests: errors propagate through without breaking context ---

  @Test
  void maybeErrorContextPropagation() {
    RuntimeException expected = new RuntimeException(EXCEPTION_MESSAGE);
    try {
      assemblePublisherUnderTrace(() -> Maybe.error(expected));
    } catch (RuntimeException e) {
      // expected
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .operationName("trace-parent")
                .root()
                .error()
                .tags(defaultTags(), error(RuntimeException.class, EXCEPTION_MESSAGE)),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags())));
  }

  @Test
  void flowableErrorContextPropagation() {
    RuntimeException expected = new RuntimeException(EXCEPTION_MESSAGE);
    try {
      assemblePublisherUnderTrace(() -> Flowable.error(expected));
    } catch (RuntimeException e) {
      // expected
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .operationName("trace-parent")
                .root()
                .error()
                .tags(defaultTags(), error(RuntimeException.class, EXCEPTION_MESSAGE)),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags())));
  }

  @Test
  void maybeStepErrorContextPropagation() {
    try {
      assemblePublisherUnderTrace(
          () ->
              Maybe.just(1)
                  .map(RxJava3Test::addOneFunc)
                  .map(
                      i -> {
                        throw new RuntimeException(EXCEPTION_MESSAGE);
                      }));
    } catch (RuntimeException e) {
      // expected
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .operationName("trace-parent")
                .root()
                .error()
                .tags(defaultTags(), error(RuntimeException.class, EXCEPTION_MESSAGE)),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  // --- Cancel tests: cancellation does not break context ---

  @Test
  void maybeCancelContextPropagation() {
    cancelUnderTrace(() -> Maybe.just(1));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags())));
  }

  @Test
  void flowableCancelContextPropagation() {
    cancelUnderTrace(() -> Flowable.fromIterable(Arrays.asList(5, 6)));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags())));
  }

  // --- Chain tests: spans in concatenated publishers are children of publisher-parent ---

  @Test
  void chainedMaybeContextPropagation() {
    assemblePublisherUnderTrace(
        () ->
            Maybe.just(1)
                .map(RxJava3Test::addOneFunc)
                .map(RxJava3Test::addOneFunc)
                .concatWith(Maybe.just(1).map(RxJava3Test::addOneFunc)));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  // --- Subscription time context: parent is determined at subscribe, not at assembly ---

  @Test
  void maybeSpansParentedFromSubscriptionTime() {
    Maybe<Integer> maybe = Maybe.just(42).map(RxJava3Test::addOneFunc).map(RxJava3Test::addTwoFunc);

    AgentSpan traceParent = startSpan("test", "trace-parent");
    AgentScope traceScope = activateSpan(traceParent);
    try {
      maybe.blockingGet();
    } finally {
      traceScope.close();
      traceParent.finish();
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("addOne").childOfIndex(0).tags(defaultTags()),
            span().operationName("addTwo").childOfIndex(0).tags(defaultTags())));
  }

  // --- Thread boundary test: context propagates across schedulers ---

  @Test
  void threadBoundaryCrossing() {
    assemblePublisherUnderTrace(
        () -> Maybe.just(1).subscribeOn(Schedulers.io()).map(RxJava3Test::addOneFunc));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags())));
  }

  // --- Intermediate span test: context at subscribe is correctly captured ---

  @Test
  void intermediateSpanContextPropagation() {
    assemblePublisherUnderTraceWithIntermediate(() -> Maybe.just(1).map(RxJava3Test::addOneFunc));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("trace-parent").root().tags(defaultTags()),
            span().operationName("publisher-parent").childOfIndex(0).tags(defaultTags()),
            span().operationName("intermediate").childOfIndex(1).tags(defaultTags()),
            span().operationName("addOne").childOfIndex(1).tags(defaultTags()),
            span().operationName("addTwo").childOfIndex(1).tags(defaultTags())));
  }

  // --- Parallel flowable: library-behavior smoke tests ---
  // These verify that parallel flowable operations complete correctly under instrumentation
  // (i.e., the agent doesn't break library functionality). Context propagation for parallel
  // streams is inherently non-deterministic and is covered by the sequential tests above.

  @Test
  void parallelFlowableOnNewThreadScheduler() {
    List<String> values =
        Flowable.fromIterable(Arrays.asList(1, 2, 3, 4))
            .parallel()
            .runOn(Schedulers.newThread())
            .flatMap(
                num ->
                    Maybe.just(num.toString() + " on " + Thread.currentThread().getName())
                        .toFlowable())
            .sequential()
            .toList()
            .blockingGet();

    assertEquals(4, values.size(), "Expected 4 values, got " + values.size());
  }

  @Test
  void parallelFlowableOnComputationScheduler() {
    List<String> values =
        Flowable.fromIterable(Arrays.asList(1, 2, 3, 4))
            .parallel()
            .runOn(Schedulers.computation())
            .flatMap(
                num ->
                    Maybe.just(num.toString() + " on " + Thread.currentThread().getName())
                        .toFlowable())
            .sequential()
            .toList()
            .blockingGet();

    assertEquals(4, values.size(), "Expected 4 values, got " + values.size());
  }

  // --- Helper methods ---

  @SuppressWarnings("unchecked")
  private <T> T assemblePublisherUnderTrace(PublisherSupplier<T> publisherSupplier) {
    AgentSpan traceParent = startSpan("test", "trace-parent");
    AgentScope traceScope = activateSpan(traceParent);

    AgentSpan span = startSpan("test", "publisher-parent");
    AgentScope scope = activateSpan(span);

    Object publisher = publisherSupplier.get();
    try {
      if (publisher instanceof Maybe) {
        return (T) ((Maybe<?>) publisher).blockingGet();
      } else if (publisher instanceof Flowable) {
        return (T) ((Flowable<?>) publisher).toList().blockingGet();
      } else if (publisher instanceof Single) {
        return (T) ((Single<?>) publisher).blockingGet();
      } else if (publisher instanceof Observable) {
        return (T) ((Observable<?>) publisher).toList().blockingGet();
      }
      throw new RuntimeException("Unknown publisher: " + publisher);
    } catch (RuntimeException e) {
      traceParent.setError(true);
      traceParent.addThrowable(e);
      throw e;
    } finally {
      span.finish();
      scope.close();
      traceScope.close();
      traceParent.finish();
    }
  }

  private void cancelUnderTrace(PublisherSupplier<?> publisherSupplier) {
    AgentSpan traceParent = startSpan("test", "trace-parent");
    AgentScope traceScope = activateSpan(traceParent);

    AgentSpan span = startSpan("test", "publisher-parent");
    AgentScope scope = activateSpan(span);

    Object publisher = publisherSupplier.get();
    Flowable<?> flowable;
    if (publisher instanceof Maybe) {
      flowable = ((Maybe<?>) publisher).toFlowable();
    } else if (publisher instanceof Flowable) {
      flowable = (Flowable<?>) publisher;
    } else {
      throw new RuntimeException("Unknown publisher: " + publisher);
    }

    flowable.subscribe(
        new Subscriber<Object>() {
          @Override
          public void onSubscribe(Subscription subscription) {
            subscription.cancel();
          }

          @Override
          public void onNext(Object t) {}

          @Override
          public void onError(Throwable error) {}

          @Override
          public void onComplete() {}
        });

    scope.close();
    span.finish();
    traceScope.close();
    traceParent.finish();
  }

  @SuppressWarnings("unchecked")
  private <T> T assemblePublisherUnderTraceWithIntermediate(
      PublisherSupplier<T> publisherSupplier) {
    AgentSpan traceParent = startSpan("test", "trace-parent");
    AgentScope traceScope = activateSpan(traceParent);

    AgentSpan publisherParent = startSpan("test", "publisher-parent");
    AgentScope publisherScope = activateSpan(publisherParent);

    Object publisher = publisherSupplier.get();

    AgentSpan intermediate = startSpan("test", "intermediate");
    AgentScope intermediateScope = activateSpan(intermediate);
    try {
      if (publisher instanceof Maybe) {
        publisher = ((Maybe<Integer>) publisher).map(RxJava3Test::addTwoFunc);
      } else if (publisher instanceof Flowable) {
        publisher = ((Flowable<Integer>) publisher).map(RxJava3Test::addTwoFunc);
      }
    } finally {
      intermediate.finish();
      intermediateScope.close();
    }

    try {
      if (publisher instanceof Maybe) {
        return (T) ((Maybe<?>) publisher).blockingGet();
      } else if (publisher instanceof Flowable) {
        return (T) ((Flowable<?>) publisher).toList().blockingGet();
      }
      throw new RuntimeException("Unknown publisher: " + publisher);
    } finally {
      publisherParent.finish();
      publisherScope.close();
      traceScope.close();
      traceParent.finish();
    }
  }

  static int addOneFunc(int i) {
    AgentSpan span = startSpan("test", "addOne");
    AgentScope scope = activateSpan(span);
    try {
      return i + 1;
    } finally {
      scope.close();
      span.finish();
    }
  }

  static int addTwoFunc(int i) {
    AgentSpan span = startSpan("test", "addTwo");
    AgentScope scope = activateSpan(span);
    try {
      return i + 2;
    } finally {
      scope.close();
      span.finish();
    }
  }

  @FunctionalInterface
  interface PublisherSupplier<T> {
    Object get();
  }
}
