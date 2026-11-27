// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskBlockHelperTest {
  private static final long TOKEN = 313L;

  @Test
  void nullIntegrationRejectsEntry() {
    assertEquals(0L, TaskBlockHelper.begin(null));
  }

  @Test
  void zeroTokenDoesNotDispatchCompletion() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.beginTaskBlock()).thenReturn(0L);

    long token = TaskBlockHelper.begin(profiling);
    TaskBlockHelper.finish(profiling, token);

    verify(profiling).beginTaskBlock();
    verify(profiling, never()).endTaskBlock(0L, 0L, 0L);
  }

  @Test
  void nonzeroTokensAreCompletedWithTheAcceptingIntegration() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);

    TaskBlockHelper.finish(profiling, TOKEN);
    TaskBlockHelper.finish(profiling, Long.MIN_VALUE);

    verify(profiling).endTaskBlock(TOKEN, 0L, 0L);
    verify(profiling).endTaskBlock(Long.MIN_VALUE, 0L, 0L);
  }

  @Test
  void rejectedCompletionIsCountedAsDropped() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.endTaskBlock(TOKEN, 0L, 0L)).thenReturn(false);
    long before = TaskBlockHelper.droppedCompletions();

    TaskBlockHelper.finish(profiling, TOKEN);

    assertEquals(before + 1, TaskBlockHelper.droppedCompletions());
  }

  @Test
  void completionFailureIsCountedAsDropped() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    doThrow(new IllegalStateException("exit")).when(profiling).endTaskBlock(TOKEN, 0L, 0L);
    long before = TaskBlockHelper.droppedCompletions();

    TaskBlockHelper.finish(profiling, TOKEN);

    assertEquals(before + 1, TaskBlockHelper.droppedCompletions());
  }

  @Test
  void entryAndExitFailuresAreContained() {
    ProfilingContextIntegration entryFailure = mock(ProfilingContextIntegration.class);
    when(entryFailure.beginTaskBlock()).thenThrow(new IllegalStateException("entry"));
    ProfilingContextIntegration exitFailure = mock(ProfilingContextIntegration.class);
    doThrow(new IllegalStateException("exit")).when(exitFailure).endTaskBlock(TOKEN, 0L, 0L);

    assertEquals(0L, TaskBlockHelper.begin(entryFailure));
    assertDoesNotThrow(() -> TaskBlockHelper.finish(exitFailure, TOKEN));
  }

  @Test
  void normalSleepBalancesAcceptedToken() throws InterruptedException {
    ProfilingContextIntegration profiling = acceptedIntegration();

    TaskBlockHelper.sleep(profiling, 1L);

    verify(profiling).endTaskBlock(TOKEN, 0L, 0L);
  }

  @Test
  void zeroDurationSleepDoesNotOpenTaskBlock() throws InterruptedException {
    ProfilingContextIntegration profiling = acceptedIntegration();

    TaskBlockHelper.sleep(profiling, 0L);

    verify(profiling, never()).beginTaskBlock();
    verify(profiling, never()).endTaskBlock(anyLong(), anyLong(), anyLong());
  }

  @Test
  void zeroDurationLongIntAndTimeUnitSleepsDoNotOpenTaskBlock() throws InterruptedException {
    ProfilingContextIntegration profiling = acceptedIntegration();

    TaskBlockHelper.sleep(profiling, 0L, 0);
    TaskBlockHelper.sleep(profiling, TimeUnit.NANOSECONDS, 0L);

    verify(profiling, never()).beginTaskBlock();
    verify(profiling, never()).endTaskBlock(anyLong(), anyLong(), anyLong());
  }

  @Test
  void interruptedSleepBalancesAcceptedTokenBeforeRethrowing() {
    ProfilingContextIntegration profiling = acceptedIntegration();
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> TaskBlockHelper.sleep(profiling, 1_000L));
    } finally {
      Thread.interrupted();
    }

    verify(profiling).endTaskBlock(TOKEN, 0L, 0L);
  }

  @Test
  void negativeMillisSleepThrowsWithoutOpeningTaskBlock() {
    ProfilingContextIntegration profiling = acceptedIntegration();

    assertThrows(IllegalArgumentException.class, () -> TaskBlockHelper.sleep(profiling, -1L));

    verify(profiling, never()).beginTaskBlock();
    verify(profiling, never()).endTaskBlock(anyLong(), anyLong(), anyLong());
  }

  @Test
  void longIntAndTimeUnitSleepsBalanceAcceptedTokens() throws InterruptedException {
    ProfilingContextIntegration profiling = acceptedIntegration();

    TaskBlockHelper.sleep(profiling, 0L, 1);
    TaskBlockHelper.sleep(profiling, TimeUnit.NANOSECONDS, 1L);

    verify(profiling, times(2)).endTaskBlock(TOKEN, 0L, 0L);
  }

  private static ProfilingContextIntegration acceptedIntegration() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.beginTaskBlock()).thenReturn(TOKEN);
    return profiling;
  }
}
