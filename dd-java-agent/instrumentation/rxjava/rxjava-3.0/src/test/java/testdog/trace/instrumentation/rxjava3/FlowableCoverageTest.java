package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.flowables.ConnectableFlowable;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

class FlowableCoverageTest extends AbstractInstrumentationTest {
  @Test
  void flowableArrayDelivery() {
    Evidence e = new Evidence();
    try (Evidence ignored = e) {
      Flowable<String> source = Flowable.fromArray("one", "two", "three");
      Flowable<Long> count = watch(source.count().toFlowable(), e, "count");
      Flowable<String> skip = watch(source.skip(1).take(1), e, "skip");
      Flowable<String> last = watch(source.takeLast(1), e, "last");
      Flowable<String> boundedSource = watch(source, e, "bounded");
      Flowable<String> unbounded = watch(source, e, "unbounded");
      e.release();
      count.test().assertResult(3L);
      skip.test().assertResult("two");
      last.test().assertResult("three");
      TestSubscriber<String> bounded = boundedSource.test(0L);
      try {
        bounded.assertEmpty();
        bounded.request(1);
        bounded.assertValues("one").assertNotComplete();
        bounded.request(2);
        bounded.assertResult("one", "two", "three");
      } finally {
        bounded.cancel();
      }
      unbounded.test().assertResult("one", "two", "three");
    }
    e.check();
  }

  @Test
  void flowableCountError() {
    Evidence e = new Evidence();
    RuntimeException failure = new RuntimeException("count failure");
    AtomicInteger calls = new AtomicInteger();
    try (Evidence ignored = e) {
      Flowable<Object> source =
          Flowable.error(
              () -> {
                calls.incrementAndGet();
                e.observe("supplier");
                return failure;
              });
      Flowable<Long> counted = watch(source.count().toFlowable(), e, "count-error");
      e.release();
      counted.test().assertNoValues().assertNotComplete().assertError(error -> error == failure);
      assertEquals(1, calls.get());
    }
    e.check();
  }

  @Test
  void flowableTakeCancel() {
    Evidence e = new Evidence();
    AtomicInteger cancellations = new AtomicInteger();
    List<Integer> upstream = new ArrayList<>();
    try (Evidence ignored = e) {
      Flowable<Integer> source =
          Flowable.just(1, 2, 3)
              .doOnNext(
                  value -> {
                    upstream.add(value);
                    e.observe("upstream-next");
                  })
              .doOnCancel(
                  () -> {
                    cancellations.incrementAndGet();
                    e.observe("cancel");
                  });
      Flowable<Integer> taken = watch(source.take(1), e, "take");
      e.release();
      taken.test().assertResult(1);
      assertEquals(asList(1), upstream);
      assertEquals(1, cancellations.get());
    }
    e.check();
  }

  @Test
  void flowableReduce() {
    Evidence e = new Evidence();
    List<String> calls = new ArrayList<>();
    try (Evidence ignored = e) {
      Flowable<Integer> reduced =
          Flowable.just(1, 2, 3, 4)
              .reduce(
                  (left, right) -> {
                    calls.add(left + "+" + right);
                    e.observe("reduce");
                    return left + right;
                  })
              .toFlowable();
      Flowable<Integer> sum = watch(reduced, e, "sum");
      e.release();
      sum.test().assertResult(10);
      assertEquals(asList("1+2", "3+3", "6+4"), calls);
    }
    e.check();
  }

  @Test
  void flowableTestScheduler() {
    Evidence e = new Evidence();
    TestScheduler scheduler = new TestScheduler();
    TestSubscriber<Integer> subscriber = null;
    try (Evidence ignored = e) {
      subscriber = watch(Flowable.fromArray(1, 2).subscribeOn(scheduler), e, "scheduled").test();
      subscriber.assertEmpty();
      e.release();
      assertSame(e.original, Context.current());
      assertSame(e.originalSpan, activeSpan());
      scheduler.advanceTimeBy(1, MILLISECONDS);
      subscriber.assertResult(1, 2);
      assertSame(e.original, Context.current());
      assertSame(e.originalSpan, activeSpan());
    } finally {
      if (subscriber != null) subscriber.cancel();
    }
    e.check();
  }

  @Test
  void flowablePublishMulticast() throws InterruptedException {
    AtomicReference<Subscriber<? super String>> upstream = new AtomicReference<>();
    AtomicInteger subscriptions = new AtomicInteger();
    AtomicBoolean cancelled = new AtomicBoolean();
    Subscription sourceSubscription =
        new Subscription() {
          @Override
          public void request(long n) {
            // This source emits explicitly from the delivery worker.
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        };
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> workerError = new AtomicReference<>();
    Context[] workerContexts = new Context[2];
    AgentSpan[] workerSpans = new AgentSpan[2];
    Thread worker =
        new Thread(
            () -> {
              try {
                if (!start.await(5, SECONDS)) throw new IllegalStateException("start timeout");
                workerContexts[0] = Context.current();
                workerSpans[0] = activeSpan();
                upstream.get().onNext("first");
                upstream.get().onNext("last");
                upstream.get().onComplete();
                workerContexts[1] = Context.current();
                workerSpans[1] = activeSpan();
              } catch (Throwable failure) {
                workerError.set(failure);
              }
            },
            "flowable-raw-delivery");
    Evidence e = new Evidence();
    TestSubscriber<String> first = null;
    TestSubscriber<String> second = null;
    Disposable connection = null;
    try {
      ConnectableFlowable<String> published =
          Flowable.<String>unsafeCreate(
                  subscriber -> {
                    subscriptions.incrementAndGet();
                    e.observe("source-subscribe");
                    upstream.set(subscriber);
                    subscriber.onSubscribe(sourceSubscription);
                  })
              .takeLast(1)
              .publish();
      first = watch(published, e, "first").test();
      second = watch(published, e, "second").test();
      connection = published.connect();
      first.assertEmpty();
      second.assertEmpty();
      e.release();
      worker.start();
      start.countDown();
      worker.join(5000);
      assertFalse(worker.isAlive(), "delivery worker completed");
      assertNull(workerError.get());
      first.assertResult("last");
      second.assertResult("last");
      assertEquals(1, subscriptions.get());
      assertNotSame(e.expected, workerContexts[0]);
      assertNotSame(e.parent, workerSpans[0]);
      assertSame(workerContexts[0], workerContexts[1]);
      assertSame(workerSpans[0], workerSpans[1]);
      assertSame(e.original, Context.current());
    } finally {
      start.countDown();
      if (first != null) first.cancel();
      if (second != null) second.cancel();
      if (connection != null) connection.dispose();
      sourceSubscription.cancel();
      try {
        if (worker.isAlive()) {
          worker.interrupt();
          worker.join(5000);
        }
      } finally {
        e.close();
      }
    }
    assertTrue(cancelled.get());
    assertTrue(connection.isDisposed());
    e.check();
  }

  private static <T> Flowable<T> watch(Flowable<T> flow, Evidence e, String label) {
    return flow.doOnNext(value -> e.observe(label + "-next"))
        .doOnError(error -> e.observe(label + "-error"))
        .doOnComplete(() -> e.observe(label + "-complete"));
  }

  private final class Evidence implements AutoCloseable {
    final Context original = Context.current();
    final AgentSpan originalSpan = activeSpan();
    final AgentSpan parent = startSpan("test", "parent");
    final ContextScope scope = activateSpan(parent);
    final Context expected = Context.current();
    final List<Context> contexts = new ArrayList<>();
    final List<AgentSpan> spans = new ArrayList<>();
    final List<String> labels = new ArrayList<>();
    boolean released;

    synchronized void observe(String label) {
      contexts.add(Context.current());
      spans.add(activeSpan());
      labels.add(label);
      if (labels.size() == 1) {
        startSpan("test", "callback").finish();
      }
    }

    void release() {
      if (!released) {
        scope.close();
        released = true;
      }
    }

    @Override
    public void close() {
      release();
      parent.finish();
    }

    void check() {
      assertSame(original, Context.current());
      assertSame(originalSpan, activeSpan());
      assertFalse(labels.isEmpty());
      for (int i = 0; i < labels.size(); i++) {
        assertSame(expected, contexts.get(i), labels.get(i));
        assertSame(parent, spans.get(i), labels.get(i));
      }
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfIndex(0).operationName("callback")));
    }
  }
}
