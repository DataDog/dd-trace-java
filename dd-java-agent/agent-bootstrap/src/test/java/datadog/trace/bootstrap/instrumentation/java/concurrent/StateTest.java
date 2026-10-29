package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StateTest {
  private static final int ITERATIONS = 100_000;

  @Test
  void claimsContinuationOnlyOnce() {
    State state = State.FACTORY.create();
    CountingContinuation continuation = new CountingContinuation();
    assertNull(state.getAndResetContinuation());
    assertTrue(state.setOrCancelContinuation(continuation));
    assertSame(continuation, state.getAndResetContinuation());
    assertNull(state.getAndResetContinuation());
    state.closeContinuation();
    assertEquals(0, continuation.releases.get());
  }

  @Test
  void closesContinuationOnlyOnce() {
    State state = State.FACTORY.create();
    CountingContinuation continuation = new CountingContinuation();
    assertTrue(state.setOrCancelContinuation(continuation));
    state.closeContinuation();
    state.closeContinuation();
    assertNull(state.getAndResetContinuation());
    assertEquals(1, continuation.releases.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(60)
  void onlyOneRacingThreadConsumesContinuation(boolean cancel) throws Exception {
    State state = State.FACTORY.create();
    CountingContinuation continuation = new CountingContinuation();
    CyclicBarrier barrier = new CyclicBarrier(3);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> race(state, barrier, false));
      Future<?> second = executor.submit(() -> race(state, barrier, cancel));
      for (int i = 0; i < ITERATIONS; i++) {
        assertTrue(state.setOrCancelContinuation(continuation));
        barrier.await();
        barrier.await();
        int resumes = continuation.resumes.getAndSet(0);
        int releases = continuation.releases.getAndSet(0);
        assertEquals(1, resumes + releases, "iteration " + i);
        assertNull(state.getAndResetContinuation());
      }
      first.get();
      second.get();
    } finally {
      executor.shutdownNow();
    }
  }

  private static void race(State state, CyclicBarrier barrier, boolean cancel) {
    try {
      for (int i = 0; i < ITERATIONS; i++) {
        barrier.await();
        if (cancel) {
          state.closeContinuation();
        } else {
          ContextContinuation continuation = state.getAndResetContinuation();
          if (continuation != null) {
            try (ContextScope scope = continuation.resume()) {
              // Closing the scope completes the successful claim.
            }
          }
        }
        barrier.await();
      }
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static class CountingContinuation implements ContextContinuation {
    final AtomicInteger resumes = new AtomicInteger();
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
      resumes.incrementAndGet();
      return context().attach();
    }

    @Override
    public void release() {
      releases.incrementAndGet();
    }
  }
}
