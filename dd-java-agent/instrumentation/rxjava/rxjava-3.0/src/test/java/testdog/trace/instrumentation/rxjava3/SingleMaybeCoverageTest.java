package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.MaybeEmitter;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SingleMaybeCoverageTest extends AbstractInstrumentationTest {
  @Test
  void singleCreateSuccess() {
    try (Evidence e = new Evidence()) {
      e.assemble(
              () ->
                  Single.unsafeCreate(
                          observer -> {
                            e.observe("source");
                            observer.onSubscribe(Disposable.empty());
                            observer.onSuccess("Hello");
                          })
                      .toFlowable())
          .subscribe(e::value, e::error, e::complete);
      e.result(singletonList("Hello"), 1, null);
    }
  }

  @Test
  void singleCreateError() {
    RuntimeException failure = new RuntimeException("single");
    try (Evidence e = new Evidence()) {
      e.assemble(
              () ->
                  Single.unsafeCreate(
                          observer -> {
                            e.observe("source");
                            observer.onSubscribe(Disposable.empty());
                            observer.onError(failure);
                          })
                      .toFlowable())
          .subscribe(e::value, e::error, e::complete);
      e.result(emptyList(), 0, failure);
    }
  }

  @Test
  void singleZip() {
    try (Evidence e = new Evidence()) {
      e.assemble(
              () ->
                  Single.zip(
                          Single.just("A"),
                          Single.just("B"),
                          (a, b) -> {
                            e.observe("zipper");
                            return a + b;
                          })
                      .toFlowable())
          .subscribe(e::value, e::error, e::complete);
      e.result(singletonList("AB"), 1, null);
    }
  }

  @Test
  void singleAsyncHandoff() throws Exception {
    ExecutorService first = newSingleThreadExecutor();
    ExecutorService second = newSingleThreadExecutor();
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Disposable> subscription = new AtomicReference<>();
    AtomicReference<Thread> firstThread = new AtomicReference<>();
    AtomicReference<Thread> secondThread = new AtomicReference<>();
    try (Evidence e = new Evidence()) {
      try {
        Object[] firstOriginal =
            first.submit(() -> new Object[] {Context.current(), activeSpan()}).get(5, SECONDS);
        Object[] secondOriginal =
            second.submit(() -> new Object[] {Context.current(), activeSpan()}).get(5, SECONDS);
        e.run(
            () ->
                subscription.set(
                    Single.just("Hello")
                        .subscribeOn(Schedulers.from(first))
                        .map(
                            value -> {
                              if (!release.await(5, SECONDS))
                                throw new IllegalStateException("gate timed out");
                              firstThread.set(Thread.currentThread());
                              e.observe("first map");
                              return value;
                            })
                        .observeOn(Schedulers.from(second))
                        .map(
                            value -> {
                              secondThread.set(Thread.currentThread());
                              e.observe("second map");
                              return value;
                            })
                        .toFlowable()
                        .subscribe(e::value, e::error, e::complete)));
        assertSame(e.original, Context.current());
        release.countDown();
        assertTrue(e.done.await(5, SECONDS), "terminal timeout");
        assertNotSame(firstThread.get(), secondThread.get());
        e.result(singletonList("Hello"), 1, null);
        assertSame(e.original, Context.current());
        assertSame(e.originalSpan, activeSpan());
        // Sentinels run after each scheduler task unwinds on the same worker thread.
        first
            .submit(
                () -> {
                  assertSame(firstThread.get(), Thread.currentThread());
                  assertSame(firstOriginal[0], Context.current());
                  assertSame(firstOriginal[1], activeSpan());
                })
            .get(5, SECONDS);
        second
            .submit(
                () -> {
                  assertSame(secondThread.get(), Thread.currentThread());
                  assertSame(secondOriginal[0], Context.current());
                  assertSame(secondOriginal[1], activeSpan());
                })
            .get(5, SECONDS);
      } finally {
        release.countDown();
        if (subscription.get() != null) subscription.get().dispose();
        first.shutdownNow();
        second.shutdownNow();
        first.awaitTermination(5, SECONDS);
        second.awaitTermination(5, SECONDS);
      }
      assertTrue(first.isTerminated(), "first executor terminated");
      assertTrue(second.isTerminated(), "second executor terminated");
    }
  }

  @Test
  void maybeCreateCallbackError() {
    RuntimeException failure = new RuntimeException("callback");
    try (Evidence e = new Evidence()) {
      e.assemble(
              () ->
                  Maybe.create(
                      emitter -> {
                        e.observe("source");
                        throw failure;
                      }))
          .subscribe(e::value, e::error, e::complete);
      e.result(emptyList(), 0, failure);
    }
  }

  @Test
  void maybeCreateDispose() {
    AtomicReference<MaybeEmitter<Integer>> emitter = new AtomicReference<>();
    AtomicReference<Disposable> subscription = new AtomicReference<>();
    AtomicInteger cleanup = new AtomicInteger();
    Context[] disposalContext = new Context[1];
    AgentSpan[] disposalSpan = new AgentSpan[1];
    try (Evidence e = new Evidence()) {
      try {
        Maybe<Integer> source =
            e.assemble(
                () ->
                    Maybe.<Integer>create(
                        sink -> {
                          e.observe("source");
                          emitter.set(sink);
                          sink.setCancellable(
                              () -> {
                                disposalContext[0] = Context.current();
                                disposalSpan[0] = activeSpan();
                                cleanup.incrementAndGet();
                              });
                        }));
        subscription.set(source.subscribe(e::value, e::error, e::complete));
        e.assertRestored();
        subscription.get().dispose();
        assertTrue(emitter.get().isDisposed());
        assertTrue(subscription.get().isDisposed());
        emitter.get().onSuccess(1);
        subscription.get().dispose();
        assertEquals(1, cleanup.get());
        assertSame(e.original, disposalContext[0]);
        assertSame(e.originalSpan, disposalSpan[0]);
        e.result(emptyList(), 0, null);
      } finally {
        if (subscription.get() != null) subscription.get().dispose();
      }
    }
  }

  @Test
  void maybeCacheSuccess() {
    cached(false);
  }

  @Test
  void maybeCacheEmpty() {
    cached(true);
  }

  private void cached(boolean empty) {
    AtomicInteger subscriptions = new AtomicInteger();
    try (Evidence e = new Evidence()) {
      Maybe<Integer> cached =
          e.assemble(
              () ->
                  (empty ? Maybe.<Integer>empty() : Maybe.just(1))
                      .doOnSubscribe(
                          d -> {
                            e.observe("source subscription");
                            subscriptions.incrementAndGet();
                          })
                      .cache());
      cached.subscribe(e::value, e::error, e::complete);
      e.result(empty ? emptyList() : singletonList(1), empty ? 1 : 0, null);
      cached.subscribe(e::value, e::error, e::complete);
      e.result(empty ? emptyList() : asList(1, 1), empty ? 2 : 0, null);
      assertEquals(1, subscriptions.get());
    }
  }

  @Test
  void maybeFinallyError() {
    RuntimeException failure = new RuntimeException("finally");
    AtomicInteger cleanup = new AtomicInteger();
    try (Evidence e = new Evidence()) {
      e.assemble(
              () ->
                  Maybe.error(failure)
                      .doFinally(
                          () -> {
                            e.observe("finally");
                            cleanup.incrementAndGet();
                          }))
          .subscribe(e::value, e::error, e::complete);
      e.result(emptyList(), 0, failure);
      assertEquals(1, cleanup.get());
    }
  }

  @Test
  void maybeTimerDispose() {
    TestScheduler scheduler = new TestScheduler();
    AtomicInteger cleanup = new AtomicInteger();
    Context[] disposalContext = new Context[1];
    AgentSpan[] disposalSpan = new AgentSpan[1];
    AtomicReference<Disposable> subscription = new AtomicReference<>();
    try (Evidence e = new Evidence()) {
      try {
        Maybe<Long> timer =
            e.assemble(
                () ->
                    Maybe.timer(1, SECONDS, scheduler)
                        .doOnSubscribe(ignored -> e.observe("subscribe"))
                        .doOnDispose(
                            () -> {
                              disposalContext[0] = Context.current();
                              disposalSpan[0] = activeSpan();
                              cleanup.incrementAndGet();
                            }));
        subscription.set(timer.subscribe(e::value, e::error, e::complete));
        e.assertRestored();
        subscription.get().dispose();
        assertTrue(subscription.get().isDisposed());
        scheduler.advanceTimeBy(2, SECONDS);
        assertSame(e.original, Context.current());
        assertEquals(1, cleanup.get());
        assertSame(e.original, disposalContext[0]);
        assertSame(e.originalSpan, disposalSpan[0]);
        e.result(emptyList(), 0, null);
      } finally {
        if (subscription.get() != null) subscription.get().dispose();
      }
    }
  }

  private final class Evidence implements AutoCloseable {
    final Context original = Context.current();
    final AgentSpan originalSpan = activeSpan();
    final AgentSpan parent = startSpan("test", "parent");
    final CountDownLatch done = new CountDownLatch(1);
    final List<Context> contexts = new ArrayList<>();
    final List<AgentSpan> spans = new ArrayList<>();
    final List<String> labels = new ArrayList<>();
    final List<Object> values = new ArrayList<>();
    Context expected;
    Throwable failure;
    int errors;
    int completions;

    <T> T assemble(Supplier<T> factory) {
      T result;
      try (ContextScope scope = activateSpan(parent)) {
        expected = Context.current();
        result = factory.get();
      }
      assertRestored();
      return result;
    }

    void assertRestored() {
      assertSame(original, Context.current());
      assertSame(originalSpan, activeSpan());
    }

    void run(Runnable action) {
      try (ContextScope scope = activateSpan(parent)) {
        if (expected == null) expected = Context.current();
        action.run();
      }
      assertSame(original, Context.current());
      assertSame(originalSpan, activeSpan());
    }

    synchronized void observe(String label) {
      labels.add(label);
      contexts.add(Context.current());
      spans.add(activeSpan());
      if (labels.size() == 1) {
        AgentSpan child = startSpan("test", "callback");
        child.finish();
      }
    }

    void value(Object value) {
      observe("value");
      values.add(value);
    }

    void error(Throwable error) {
      observe("error");
      failure = error;
      errors++;
      done.countDown();
    }

    void complete() {
      observe("complete");
      completions++;
      done.countDown();
    }

    void result(List<?> wanted, int complete, Throwable error) {
      assertEquals(wanted, values);
      assertEquals(complete, completions);
      assertEquals(error == null ? 0 : 1, errors);
      assertSame(error, failure);
    }

    @Override
    public void close() {
      parent.finish();
      assertTrue(!labels.isEmpty(), "callbacks must execute");
      for (int i = 0; i < labels.size(); i++) {
        assertSame(expected, contexts.get(i), labels.get(i));
        assertSame(parent, spans.get(i), labels.get(i));
        assertSame(parent, AgentSpan.fromContext(contexts.get(i)), labels.get(i));
      }
      assertSame(original, Context.current());
      assertSame(originalSpan, activeSpan());
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfIndex(0).operationName("callback")));
    }
  }
}
