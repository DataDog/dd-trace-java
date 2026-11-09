package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.observables.ConnectableObservable;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObservableMulticastTest extends AbstractInstrumentationTest {
  @Test
  void untracedOperatorsDoNotAllocateTracingObservers() {
    assertSame(Context.root(), Context.current());
    AtomicReference<Observer<? super Integer>> sourceObserver = new AtomicReference<>();
    Observable.<Integer>unsafeCreate(
            observer -> {
              sourceObserver.set(observer);
              observer.onSubscribe(Disposable.empty());
              observer.onNext(1);
              observer.onNext(2);
              observer.onComplete();
            })
        .map(value -> value + 1)
        .filter(value -> (value & 1) == 0)
        .map(value -> value * 2)
        .reduce(0, Integer::sum)
        .toObservable()
        .test()
        .assertResult(4);
    assertNotEquals(
        "datadog.trace.instrumentation.rxjava3.TracingObserver",
        sourceObserver.get().getClass().getName());
    assertSame(Context.root(), Context.current());
    tracer.flush();
    assertEquals(0, writer.getTraceCount());
  }

  @ParameterizedTest(name = "multicast preserves each subscriber parent, error={0}")
  @ValueSource(booleans = {false, true})
  void preservesSubscriberContext(boolean error) throws Exception {
    Context original = Context.current();
    AgentSpan originalSpan = activeSpan();
    AtomicInteger subscriptions = new AtomicInteger();
    AtomicInteger disposals = new AtomicInteger();
    AtomicReference<Observer<? super String>> upstream = new AtomicReference<>();
    Disposable source = Disposable.fromRunnable(disposals::incrementAndGet);
    // The shared publisher is assembled without a parent. Each subscription owns its Context.
    ConnectableObservable<String> published =
        Observable.<String>unsafeCreate(
                observer -> {
                  subscriptions.incrementAndGet();
                  upstream.set(observer);
                  observer.onSubscribe(source);
                })
            .takeLast(1)
            .publish();
    AgentSpan firstParent = startSpan("test", "first-parent");
    AgentSpan secondParent = startSpan("test", "second-parent");
    AgentSpan deliveryParent = startSpan("test", "delivery-parent");
    ContextObserver first;
    ContextObserver second;
    ContextObserver untraced = new ContextObserver(Context.root(), null);
    Disposable connection = null;
    Thread worker = null;
    RuntimeException failure = new RuntimeException("upstream failure");
    try {
      try (ContextScope ignored = activateSpan(firstParent)) {
        first = new ContextObserver(Context.current(), firstParent);
        published.subscribe(first);
      }
      try (ContextScope ignored = activateSpan(secondParent)) {
        second = new ContextObserver(Context.current(), secondParent);
        published.subscribe(second);
      }
      try (ContextScope ignored = activateSpan(firstParent)) {
        try (ContextScope empty = Context.root().attach()) {
          assertSame(Context.root(), Context.current());
          assertNull(activeSpan());
          published.subscribe(untraced);
        }
        assertSame(firstParent, activeSpan(), "empty subscription scope must restore its caller");
      }
      try (ContextScope ignored = activateSpan(deliveryParent)) {
        connection = published.connect();
      }
      assertEquals(1, subscriptions.get());
      assertSame(original, Context.current());
      FutureTask<Void> delivery =
          new FutureTask<>(
              new Callable<Void>() {
                @Override
                public Void call() {
                  Context before = Context.current();
                  AgentSpan beforeSpan = activeSpan();
                  assertSame(original, before);
                  assertSame(originalSpan, beforeSpan);
                  try (ContextScope ignored = activateSpan(deliveryParent)) {
                    Context deliveryContext = Context.current();
                    upstream.get().onNext("first");
                    upstream.get().onNext("last");
                    if (error) {
                      upstream.get().onError(failure);
                    } else {
                      upstream.get().onComplete();
                    }
                    assertSame(deliveryContext, Context.current());
                    assertSame(deliveryParent, activeSpan());
                  }
                  assertSame(before, Context.current(), "delivery thread Context must be restored");
                  assertSame(beforeSpan, activeSpan());
                  return null;
                }
              });
      worker = new Thread(delivery, "observable-multicast-delivery");
      worker.start();
      delivery.get(5, SECONDS);
      for (ContextObserver observer : new ContextObserver[] {first, second, untraced}) {
        if (error) {
          observer.assertFailure(RuntimeException.class);
          observer.assertError(failure);
        } else {
          observer.assertResult("last");
        }
        observer.assertContext(error ? 1 : 2);
      }
      assertSame(original, Context.current());
      assertSame(originalSpan, activeSpan());
    } finally {
      if (worker != null) {
        worker.interrupt();
        worker.join(5000);
      }
      if (connection != null) connection.dispose();
      untraced.dispose();
      source.dispose();
      firstParent.finish();
      secondParent.finish();
      deliveryParent.finish();
    }
    assertFalse(worker.isAlive(), "delivery thread must terminate");
    assertEquals(1, disposals.get());
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("first-parent"),
            span().childOfIndex(0).operationName("callback")),
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("second-parent"),
            span().childOfIndex(0).operationName("callback")),
        trace(span().root().operationName("delivery-parent")));
  }

  private static final class ContextObserver extends TestObserver<String> {
    private final Context expected;
    private final AgentSpan parent;
    private final List<Context> contexts = new ArrayList<>();
    private final List<AgentSpan> spans = new ArrayList<>();

    ContextObserver(Context expected, AgentSpan parent) {
      this.expected = expected;
      this.parent = parent;
    }

    @Override
    public void onNext(String value) {
      record();
      super.onNext(value);
    }

    @Override
    public void onComplete() {
      terminal();
      super.onComplete();
    }

    @Override
    public void onError(Throwable failure) {
      terminal();
      super.onError(failure);
    }

    private void record() {
      contexts.add(Context.current());
      spans.add(activeSpan());
    }

    private void terminal() {
      record();
      if (parent != null) {
        AgentSpan child = startSpan("test", "callback");
        child.finish();
      }
    }

    void assertContext(int callbacks) {
      assertEquals(callbacks, contexts.size());
      for (int i = 0; i < contexts.size(); i++) {
        assertSame(expected, contexts.get(i), "callback Context");
        assertSame(parent, spans.get(i), "callback parent");
      }
    }
  }
}
