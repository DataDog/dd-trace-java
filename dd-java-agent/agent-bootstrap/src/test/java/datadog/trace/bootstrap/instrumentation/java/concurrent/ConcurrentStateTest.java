package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.ContextStore;
import java.lang.reflect.Field;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConcurrentStateTest {
  private static final int ITERATIONS = 100_000;
  private static final Throwable FAILURE = new IllegalStateException("entry failed");
  private static Field continuationField;

  private final ContextStore<ConcurrentState, ConcurrentState> contextStore =
      mock(ContextStore.class, withSettings().stubOnly().defaultAnswer(returnsFirstArg()));

  @BeforeAll
  static void findContinuationField() throws Exception {
    continuationField = ConcurrentState.class.getDeclaredField("continuation");
    continuationField.setAccessible(true);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 10})
  void cancelAndClearReleasesOnce(int cancellations) throws Exception {
    CountingContinuation continuation = new CountingContinuation();
    ConcurrentState state = stateWithContinuation(continuation);

    for (int i = 0; i < cancellations; i++) {
      ConcurrentState.cancelAndClearContinuation(contextStore, state);
    }

    assertEquals(1, continuation.releases.get());
  }

  @Test
  void cancelThenClearReleasesOnce() throws Exception {
    CountingContinuation continuation = new CountingContinuation();
    ConcurrentState state = stateWithContinuation(continuation);

    ConcurrentState.closeScope(contextStore, state, null, FAILURE);
    ConcurrentState.cancelAndClearContinuation(contextStore, state);
    ConcurrentState.closeScope(contextStore, state, null, FAILURE);

    assertEquals(1, continuation.releases.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(60)
  void onlyOneRacingThreadReleasesContinuation(boolean cancel) throws Exception {
    AtomicReference<ConcurrentState> state = new AtomicReference<>();
    CountingContinuation continuation = new CountingContinuation();
    CyclicBarrier barrier = new CyclicBarrier(3);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> race(state, barrier, false));
      Future<?> second = executor.submit(() -> race(state, barrier, cancel));
      for (int i = 0; i < ITERATIONS; i++) {
        state.set(stateWithContinuation(continuation));
        barrier.await();
        barrier.await();
        assertEquals(1, continuation.releases.getAndSet(0), "iteration " + i);
      }
      first.get();
      second.get();
    } finally {
      executor.shutdownNow();
    }
  }

  private void race(AtomicReference<ConcurrentState> state, CyclicBarrier barrier, boolean cancel) {
    try {
      for (int i = 0; i < ITERATIONS; i++) {
        barrier.await();
        if (cancel) {
          ConcurrentState.closeScope(contextStore, state.get(), null, FAILURE);
        } else {
          ConcurrentState.cancelAndClearContinuation(contextStore, state.get());
        }
        barrier.await();
      }
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static ConcurrentState stateWithContinuation(ContextContinuation continuation)
      throws Exception {
    ConcurrentState state = ConcurrentState.FACTORY.create();
    continuationField.set(state, continuation);
    return state;
  }

  private static class CountingContinuation implements ContextContinuation {
    final AtomicInteger releases = new AtomicInteger();

    @Override
    public ContextContinuation hold() {
      return this;
    }

    @Override
    public Context context() {
      return Context.root();
    }

    @Override
    public ContextScope resume() {
      return context().attach();
    }

    @Override
    public void release() {
      releases.incrementAndGet();
    }
  }
}
