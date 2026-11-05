package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompletableCoverageTest extends AbstractInstrumentationTest {
  @Test
  void completableConcatComplete() {
    try (Probe p = new Probe()) {
      List<Integer> effects = new ArrayList<>();
      Completable concatenated =
          Completable.concatArray(
              source(p, effects, 1, null),
              source(p, effects, 2, null),
              source(p, effects, 3, null));
      p.closeScope();
      p.assertRestored();
      concatenated.subscribe(p::complete, p::error);
      p.assertRestored();
      assertEquals(asList(1, 2, 3), effects);
      assertEquals(asList("source-1", "source-2", "source-3", "complete"), p.labels);
      p.assertTerminal(1, null);
    }
  }

  @Test
  void completableConcatError() {
    RuntimeException failure = new RuntimeException("concat");
    try (Probe p = new Probe()) {
      List<Integer> effects = new ArrayList<>();
      Completable concatenated =
          Completable.concatArray(
              source(p, effects, 1, null),
              source(p, effects, 2, failure),
              source(p, effects, 3, null));
      p.closeScope();
      p.assertRestored();
      concatenated.subscribe(p::complete, p::error);
      p.assertRestored();
      assertEquals(asList(1, 2), effects);
      assertEquals(asList("source-1", "source-2", "error"), p.labels);
      p.assertTerminal(0, failure);
    }
  }

  @Test
  void completableDefer() {
    AtomicInteger suppliers = new AtomicInteger();
    List<Integer> effects = new ArrayList<>();
    try (Probe p = new Probe()) {
      Completable deferred =
          Completable.defer(
              () -> {
                p.record("supplier");
                int subscription = suppliers.incrementAndGet();
                return source(p, effects, subscription, null);
              });
      assertEquals(0, suppliers.get());
      assertTrue(effects.isEmpty());
      p.closeScope();
      p.assertRestored();
      deferred.subscribe(p::complete, p::error);
      p.assertRestored();
      assertEquals(1, suppliers.get());
      deferred.subscribe(p::complete, p::error);
      p.assertRestored();
      assertEquals(2, suppliers.get());
      assertEquals(asList(1, 2), effects);
      assertEquals(
          asList("supplier", "source-1", "complete", "supplier", "source-2", "complete"), p.labels);
      p.assertTerminal(2, null);
    }
  }

  @Test
  void completableErrorSupplier() {
    RuntimeException failure = new RuntimeException("supplier");
    AtomicInteger suppliers = new AtomicInteger();
    try (Probe p = new Probe()) {
      Completable error =
          Completable.error(
              () -> {
                suppliers.incrementAndGet();
                p.record("supplier");
                return failure;
              });
      assertEquals(0, suppliers.get());
      p.closeScope();
      p.assertRestored();
      error.subscribe(p::complete, p::error);
      p.assertRestored();
      assertEquals(1, suppliers.get());
      assertEquals(asList("supplier", "error"), p.labels);
      p.assertTerminal(0, failure);
    }
  }

  @Test
  void completableTimer() {
    TestScheduler scheduler = new TestScheduler();
    try (Probe p = new Probe()) {
      Disposable subscription =
          Completable.timer(500, MILLISECONDS, scheduler)
              .doOnComplete(() -> p.record("timer"))
              .subscribe(p::complete, p::error);
      try {
        p.closeScope();
        p.assertRestored();
        p.assertTerminal(0, null);
        scheduler.advanceTimeBy(499, MILLISECONDS);
        p.assertTerminal(0, null);
        assertTrue(p.labels.isEmpty());
        p.assertRestored();
        scheduler.advanceTimeBy(1, MILLISECONDS);
        p.assertTerminal(1, null);
        assertEquals(asList("timer", "complete"), p.labels);
        p.assertRestored();
        scheduler.advanceTimeBy(500, MILLISECONDS);
        p.assertTerminal(1, null);
        p.assertRestored();
      } finally {
        subscription.dispose();
      }
    }
  }

  @Test
  void completableSubscribeDispose() {
    PublishProcessor<Integer> processor = PublishProcessor.create();
    AtomicInteger cleanup = new AtomicInteger();
    Context[] disposalContext = new Context[1];
    AgentSpan[] disposalSpan = new AgentSpan[1];
    try (Probe p = new Probe()) {
      Completable source =
          processor
              .ignoreElements()
              .doOnSubscribe(ignored -> p.record("subscribe"))
              .doOnDispose(
                  () -> {
                    cleanup.incrementAndGet();
                    // Disposing is a caller action, outside the captured callback context.
                    disposalContext[0] = Context.current();
                    disposalSpan[0] = activeSpan();
                  });
      p.closeScope();
      p.assertRestored();
      Disposable subscription = source.subscribe(p::complete, p::error);
      try {
        p.assertRestored();
        assertFalse(subscription.isDisposed());
        assertTrue(processor.hasSubscribers());
        processor.onNext(1);
        p.assertTerminal(0, null);
        subscription.dispose();
        assertTrue(subscription.isDisposed());
        assertFalse(processor.hasSubscribers());
        assertEquals(1, cleanup.get());
        assertSame(p.original, disposalContext[0]);
        assertSame(p.originalSpan, disposalSpan[0]);
        subscription.dispose();
        assertEquals(1, cleanup.get());
        p.assertRestored();
        processor.onNext(2);
        processor.onComplete();
        p.assertTerminal(0, null);
        assertEquals(asList("subscribe"), p.labels);
        p.assertRestored();
      } finally {
        subscription.dispose();
      }
    }
  }

  private static Completable source(Probe p, List<Integer> effects, int id, Throwable failure) {
    return Completable.create(
        emitter -> {
          p.record("source-" + id);
          effects.add(id);
          if (failure == null) {
            emitter.onComplete();
          } else {
            emitter.onError(failure);
          }
        });
  }

  private final class Probe implements AutoCloseable {
    final Context original = Context.current();
    final AgentSpan originalSpan = activeSpan();
    final AgentSpan parent = startSpan("test", "parent");
    final ContextScope scope = activateSpan(parent);
    final Context expected = Context.current();
    final List<String> labels = new ArrayList<>();
    final List<Context> contexts = new ArrayList<>();
    final List<AgentSpan> spans = new ArrayList<>();
    final List<Throwable> errors = new ArrayList<>();
    int completions;
    boolean scopeClosed;

    void record(String label) {
      labels.add(label);
      contexts.add(Context.current());
      spans.add(activeSpan());
      if (labels.size() == 1) {
        startSpan("test", "callback").finish();
      }
    }

    void complete() {
      record("complete");
      completions++;
    }

    void error(Throwable failure) {
      record("error");
      errors.add(failure);
    }

    void assertTerminal(int expectedCompletions, Throwable failure) {
      assertEquals(expectedCompletions, completions);
      assertEquals(failure == null ? 0 : 1, errors.size());
      if (failure != null) {
        assertSame(failure, errors.get(0));
      }
    }

    void closeScope() {
      if (!scopeClosed) {
        scope.close();
        scopeClosed = true;
      }
    }

    void assertRestored() {
      assertSame(original, Context.current());
      assertSame(originalSpan, activeSpan());
    }

    @Override
    public void close() {
      try {
        closeScope();
      } finally {
        parent.finish();
      }
      assertRestored();
      assertFalse(labels.isEmpty());
      for (int i = 0; i < labels.size(); i++) {
        assertSame(expected, contexts.get(i), labels.get(i));
        assertSame(parent, spans.get(i), labels.get(i));
        assertSame(parent, AgentSpan.fromContext(contexts.get(i)), labels.get(i));
      }
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfIndex(0).operationName("callback")));
    }
  }
}
