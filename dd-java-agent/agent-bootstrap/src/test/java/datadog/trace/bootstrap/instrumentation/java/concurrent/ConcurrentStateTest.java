package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.ContextStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConcurrentStateTest {
  private final ExecutorService executor = Executors.newCachedThreadPool();

  private ConcurrentState state;
  private Context context;
  private ContextContinuation continuation;

  @BeforeEach
  void setUp() {
    state = ConcurrentState.FACTORY.create();
    context = mock(Context.class);
    continuation = mock(ContextContinuation.class);
    when(context.capture()).thenReturn(continuation);
    when(continuation.hold()).thenReturn(continuation);
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  void concurrentTerminalCallersReleaseOnlyOnce() throws Exception {
    assertTrue(state.captureAndSetContinuation(context));
    CyclicBarrier start = new CyclicBarrier(2);

    Future<?> first =
        executor.submit(
            () -> {
              await(start);
              state.cancelAndClearContinuation();
            });
    Future<?> second =
        executor.submit(
            () -> {
              await(start);
              state.cancelAndClearContinuation();
            });

    first.get(10, SECONDS);
    second.get(10, SECONDS);

    verify(continuation, times(1)).release();
    verify(continuation, never()).resume();
  }

  @Test
  void terminalResolutionRejectsStaleActivation() {
    assertTrue(state.captureAndSetContinuation(context));

    state.cancelAndClearContinuation();

    assertNull(state.activateAndContinueContinuation(continuation));
    verify(continuation, times(1)).release();
    verify(continuation, never()).resume();
  }

  @Test
  void terminalResolutionPreventsLaterCapture() {
    state.cancelAndClearContinuation();

    assertFalse(state.captureAndSetContinuation(context));
    verify(context, never()).capture();
  }

  @Test
  void throwableCleanupTerminatesState() {
    Object key = new Object();
    @SuppressWarnings("unchecked")
    ContextStore<Object, ConcurrentState> contextStore = mock(ContextStore.class);
    when(contextStore.get(key)).thenReturn(state);
    assertTrue(state.captureAndSetContinuation(context));

    ConcurrentState.closeScope(contextStore, key, null, new RuntimeException());

    assertNull(state.activateAndContinueContinuation(continuation));
    verify(continuation, times(1)).release();
  }

  @Test
  void terminalResolutionWaitsForActivationAdmission() throws Exception {
    assertTrue(state.captureAndSetContinuation(context));
    ContextScope scope = mock(ContextScope.class);
    CountDownLatch resumeEntered = new CountDownLatch(1);
    CountDownLatch allowResume = new CountDownLatch(1);
    CountDownLatch releaseEntered = new CountDownLatch(1);
    when(continuation.resume())
        .thenAnswer(
            invocation -> {
              resumeEntered.countDown();
              assertTrue(allowResume.await(10, SECONDS));
              return scope;
            });
    doAnswer(
            invocation -> {
              releaseEntered.countDown();
              return null;
            })
        .when(continuation)
        .release();

    Future<ContextScope> activation =
        executor.submit(() -> state.activateAndContinueContinuation(continuation));
    assertTrue(resumeEntered.await(10, SECONDS));
    Thread terminal = new Thread(state::cancelAndClearContinuation);
    terminal.start();
    awaitBlocked(terminal);

    verify(continuation, never()).release();
    allowResume.countDown();

    assertSame(scope, activation.get(10, SECONDS));
    terminal.join(SECONDS.toMillis(10));
    assertFalse(terminal.isAlive());
    assertTrue(releaseEntered.await(10, SECONDS));
    verify(continuation, times(1)).resume();
    verify(continuation, times(1)).release();
  }

  @Test
  void terminalResolutionDuringCaptureDoesNotReopenState() throws Exception {
    CountDownLatch captureEntered = new CountDownLatch(1);
    CountDownLatch allowCapture = new CountDownLatch(1);
    when(context.capture())
        .thenAnswer(
            invocation -> {
              captureEntered.countDown();
              assertTrue(allowCapture.await(10, SECONDS));
              return continuation;
            });

    Future<Boolean> capture = executor.submit(() -> state.captureAndSetContinuation(context));
    assertTrue(captureEntered.await(10, SECONDS));

    state.cancelAndClearContinuation();
    allowCapture.countDown();

    assertFalse(capture.get(10, SECONDS));
    assertNull(state.activateAndContinueContinuation(continuation));
    verify(continuation, times(1)).release();
    verify(continuation, never()).resume();
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, SECONDS);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static void awaitBlocked(Thread thread) {
    long deadline = System.nanoTime() + SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.yield();
    }
    assertSame(Thread.State.BLOCKED, thread.getState());
  }
}
