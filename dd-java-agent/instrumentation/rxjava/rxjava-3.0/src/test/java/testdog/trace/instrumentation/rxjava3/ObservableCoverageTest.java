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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ObservableCoverageTest extends AbstractInstrumentationTest {
  @Test
  void observableArrayDelivery() {
    try (Observation h = new Observation()) {
      String[] items = {"one", "two", "three"};
      Observable<String> source;
      Observable<Long> counted;
      Observable<String> middleSource;
      Observable<String> lastSource;
      try (ContextScope ignored = h.activate()) {
        source = Observable.fromArray(items);
        counted = source.doOnNext(v -> h.record("count source")).count().toObservable();
        middleSource = source.skip(1).take(1);
        lastSource = source.takeLast(1);
      }
      Probe<String> all = new Probe<>(h);
      source.subscribe(all);
      all.success(asList(items));
      Probe<Long> count = new Probe<>(h);
      counted.subscribe(count);
      count.success(asList(3L));
      Probe<String> middle = new Probe<>(h);
      middleSource.subscribe(middle);
      middle.success(asList("two"));
      Probe<String> last = new Probe<>(h);
      lastSource.subscribe(last);
      last.success(asList("three"));
    }
  }

  @Test
  void observableCountError() {
    try (Observation h = new Observation()) {
      AtomicInteger calls = new AtomicInteger();
      RuntimeException failure = new RuntimeException("count");
      Probe<Long> result = new Probe<>(h);
      Observable<Long> counted;
      try (ContextScope ignored = h.activate()) {
        counted =
            Observable.<String>error(
                    () -> {
                      h.record("error supplier");
                      calls.incrementAndGet();
                      return failure;
                    })
                .count()
                .toObservable();
      }
      counted.subscribe(result);
      assertEquals(1, calls.get());
      assertTrue(result.values.isEmpty());
      assertEquals(0, result.completions);
      assertEquals(asList(failure), result.errors);
      assertSame(failure, result.errors.get(0));
    }
  }

  @Test
  void observableTakeDispose() {
    try (Observation h = new Observation()) {
      AtomicInteger disposals = new AtomicInteger();
      List<Integer> upstream = new ArrayList<>();
      Probe<Integer> result = new Probe<>(h);
      Observable<Integer> taken;
      try (ContextScope ignored = h.activate()) {
        taken =
            Observable.just(1, 2, 3)
                .doOnNext(
                    v -> {
                      h.record("source next");
                      upstream.add(v);
                    })
                .doOnDispose(
                    () -> {
                      h.record("dispose");
                      disposals.incrementAndGet();
                    })
                .take(1);
      }
      taken.subscribe(result);
      result.success(asList(1));
      assertEquals(asList(1), upstream);
      assertEquals(1, disposals.get());
    }
  }

  @Test
  void observableReduce() {
    try (Observation h = new Observation()) {
      List<String> reductions = new ArrayList<>();
      Probe<Integer> result = new Probe<>(h);
      Maybe<Integer> reduced;
      try (ContextScope ignored = h.activate()) {
        reduced =
            Observable.just(1, 2, 3, 4)
                .reduce(
                    (a, b) -> {
                      h.record("reducer");
                      reductions.add(a + "+" + b);
                      return a + b;
                    });
      }
      reduced.subscribe(
          v -> {
            h.record("success");
            result.values.add(v);
          },
          result::onError,
          result::onComplete);
      assertEquals(asList("1+2", "3+3", "6+4"), reductions);
      assertEquals(asList(10), result.values);
      assertEquals(0, result.completions);
      assertTrue(result.errors.isEmpty());
    }
  }

  @Test
  void observableTestScheduler() {
    try (Observation h = new Observation()) {
      TestScheduler scheduler = new TestScheduler();
      Probe<Integer> result = new Probe<>(h);
      try {
        try (ContextScope ignored = h.activate()) {
          Observable.fromArray(1, 2)
              .doOnNext(v -> h.record("scheduled source"))
              .subscribeOn(scheduler)
              .subscribe(result);
        }
        assertTrue(result.values.isEmpty());
        assertEquals(0, result.completions);
        assertTrue(result.errors.isEmpty());
        assertSame(h.original, Context.current());
        assertNotSame(h.parent, activeSpan());
        scheduler.advanceTimeBy(1, MILLISECONDS);
        result.success(asList(1, 2));
        assertSame(h.original, Context.current());
      } finally {
        if (result.subscription != null) result.subscription.dispose();
      }
    }
  }

  private final class Observation implements AutoCloseable {
    final Context original = Context.current();
    final AgentSpan originalSpan = activeSpan();
    final AgentSpan parent = startSpan("test", "observable-parent");
    final List<Object[]> observations = new ArrayList<>();
    Context expected;
    boolean childCreated;

    ContextScope activate() {
      ContextScope scope = activateSpan(parent);
      expected = Context.current();
      return scope;
    }

    void record(String label) {
      observations.add(new Object[] {label, Context.current(), activeSpan()});
      if (!childCreated
          && (label.equals("next") || label.equals("error") || label.equals("success"))) {
        childCreated = true;
        AgentSpan child = startSpan("test", "observable-callback");
        child.finish();
      }
    }

    @Override
    public void close() {
      parent.finish();
      assertSame(original, Context.current());
      assertSame(originalSpan, activeSpan());
      assertFalse(observations.isEmpty());
      for (Object[] observation : observations) {
        assertSame(expected, observation[1], (String) observation[0]);
        assertSame(parent, observation[2], (String) observation[0]);
      }
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("observable-parent"),
              span().childOfIndex(0).operationName("observable-callback")));
    }
  }

  private static final class Probe<T> implements Observer<T> {
    final Observation observation;
    final List<T> values = new ArrayList<>();
    final List<Throwable> errors = new ArrayList<>();
    Disposable subscription;
    int subscriptions;
    int completions;

    Probe(Observation observation) {
      this.observation = observation;
    }

    @Override
    public void onSubscribe(Disposable disposable) {
      observation.record("downstream subscribe");
      subscription = disposable;
      subscriptions++;
    }

    @Override
    public void onNext(T value) {
      observation.record("next");
      values.add(value);
    }

    @Override
    public void onError(Throwable error) {
      observation.record("error");
      errors.add(error);
    }

    @Override
    public void onComplete() {
      observation.record("complete");
      completions++;
    }

    void success(List<T> expectedValues) {
      assertEquals(expectedValues, values);
      assertEquals(1, completions);
      assertTrue(errors.isEmpty());
    }
  }
}
