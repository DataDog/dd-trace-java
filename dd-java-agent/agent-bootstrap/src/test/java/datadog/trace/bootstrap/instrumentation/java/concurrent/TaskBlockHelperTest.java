// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import org.junit.jupiter.api.Test;

class TaskBlockHelperTest {
  private static final long TOKEN = 313L;

  @Test
  void nullIntegrationDoesNotArmState() {
    assertNull(TaskBlockHelper.captureForSleep(null));
  }

  @Test
  void zeroIsTheOnlyInvalidToken() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.beginTaskBlock(ProfilingContextIntegration.BLOCKING_STATE_SLEEPING))
        .thenReturn(0L);

    assertNull(TaskBlockHelper.captureForSleep(profiling));
    verify(profiling).beginTaskBlock(ProfilingContextIntegration.BLOCKING_STATE_SLEEPING);
    verify(profiling, never()).endTaskBlock(0L, 0L, 0L);
  }

  @Test
  void positiveTokenIsRetainedWithItsIntegration() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.beginTaskBlock(ProfilingContextIntegration.BLOCKING_STATE_SLEEPING))
        .thenReturn(TOKEN);

    TaskBlockHelper.State state = TaskBlockHelper.captureForSleep(profiling);

    assertNotNull(state);
    assertEquals(profiling, state.profiling);
    assertEquals(TOKEN, state.token);
  }

  @Test
  void negativeNonzeroTokenIsValid() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.beginTaskBlock(ProfilingContextIntegration.BLOCKING_STATE_SLEEPING))
        .thenReturn(Long.MIN_VALUE);

    TaskBlockHelper.State state = TaskBlockHelper.captureForSleep(profiling);
    TaskBlockHelper.finish(state);

    assertNotNull(state);
    verify(profiling).endTaskBlock(Long.MIN_VALUE, 0L, 0L);
  }

  @Test
  void finishAlwaysBalancesAcceptedToken() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    TaskBlockHelper.State state = new TaskBlockHelper.State(profiling, TOKEN);

    TaskBlockHelper.finish(state);

    verify(profiling).endTaskBlock(TOKEN, 0L, 0L);
  }

  @Test
  void finishIgnoresNullState() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);

    TaskBlockHelper.finish(null);

    verifyNoInteractions(profiling);
  }

  @Test
  void entryAndExitFailuresAreContained() {
    ProfilingContextIntegration entryFailure = mock(ProfilingContextIntegration.class);
    when(entryFailure.beginTaskBlock(ProfilingContextIntegration.BLOCKING_STATE_SLEEPING))
        .thenThrow(new IllegalStateException("entry"));
    ProfilingContextIntegration exitFailure = mock(ProfilingContextIntegration.class);
    doThrow(new IllegalStateException("exit")).when(exitFailure).endTaskBlock(TOKEN, 0L, 0L);

    assertDoesNotThrow(() -> TaskBlockHelper.captureForSleep(entryFailure));
    assertDoesNotThrow(() -> TaskBlockHelper.finish(new TaskBlockHelper.State(exitFailure, TOKEN)));
  }
}
