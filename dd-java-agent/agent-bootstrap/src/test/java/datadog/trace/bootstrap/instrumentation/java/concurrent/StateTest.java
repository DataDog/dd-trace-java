package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.profiling.Timing;
import org.junit.jupiter.api.Test;

class StateTest {

  @Test
  void regularAdviceCannotConsumeTpeContinuation() {
    State state = State.FACTORY.create();
    Context context = mock(Context.class);
    ContextContinuation delegate = mock(ContextContinuation.class);
    when(context.capture()).thenReturn(delegate);

    State.TpeContinuation continuation = state.captureAndSetTpeContinuation(context);

    assertNull(state.getAndResetContinuation());
    assertSame(continuation, state.getAndResetTpeContinuation());
  }

  @Test
  void overlappingTpeCaptureDoesNotReplaceOwner() {
    State state = State.FACTORY.create();
    Context first = mock(Context.class);
    Context second = mock(Context.class);
    ContextContinuation firstDelegate = mock(ContextContinuation.class);
    when(first.capture()).thenReturn(firstDelegate);

    State.TpeContinuation continuation = state.captureAndSetTpeContinuation(first);

    assertNull(state.captureAndSetTpeContinuation(second));
    assertSame(continuation, state.getAndResetTpeContinuation());
    verify(second, never()).capture();
  }

  @Test
  void transfersDuplicateGenericCaptureForSameSubmission() {
    State state = State.FACTORY.create();
    Context context = mock(Context.class);
    ContextContinuation delegate = mock(ContextContinuation.class);
    when(context.capture()).thenReturn(delegate);
    when(delegate.context()).thenReturn(context);
    state.captureAndSetContinuation(context);

    State.TpeContinuation continuation = state.captureAndSetTpeContinuation(context);

    assertSame(continuation, state.getAndResetTpeContinuation());
    verify(context).capture();
  }

  @Test
  void staleCancellationCannotCancelLaterSubmission() {
    State state = State.FACTORY.create();
    Context first = mock(Context.class);
    Context second = mock(Context.class);
    ContextContinuation firstDelegate = mock(ContextContinuation.class);
    ContextContinuation secondDelegate = mock(ContextContinuation.class);
    when(first.capture()).thenReturn(firstDelegate);
    when(second.capture()).thenReturn(secondDelegate);

    State.TpeContinuation stale = state.captureAndSetTpeContinuation(first);
    assertSame(stale, state.getAndResetTpeContinuation());
    State.TpeContinuation current = state.captureAndSetTpeContinuation(second);

    state.closeTpeContinuation(stale);

    assertSame(current, state.getAndResetTpeContinuation());
    verify(firstDelegate, never()).release();
    verify(secondDelegate, never()).release();
  }

  @Test
  void exactCancellationReleasesOwnedContinuation() {
    State state = State.FACTORY.create();
    Context context = mock(Context.class);
    ContextContinuation delegate = mock(ContextContinuation.class);
    when(context.capture()).thenReturn(delegate);
    State.TpeContinuation continuation = state.captureAndSetTpeContinuation(context);

    state.closeTpeContinuation(continuation);

    assertNull(state.getAndResetTpeContinuation());
    verify(delegate).release();
  }

  @Test
  void taggedContinuationOwnsQueueTiming() {
    State state = State.FACTORY.create();
    Context context = mock(Context.class);
    ContextContinuation delegate = mock(ContextContinuation.class);
    Timing timing = mock(Timing.class);
    when(context.capture()).thenReturn(delegate);
    State.TpeContinuation continuation = state.captureAndSetTpeContinuation(context);

    state.setTiming(timing);

    assertTrue(state.isTimed());
    assertSame(continuation, state.getAndResetTpeContinuation());
  }
}
