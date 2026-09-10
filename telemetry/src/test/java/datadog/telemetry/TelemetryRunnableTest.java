package datadog.telemetry;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.telemetry.metric.MetricPeriodicAction;
import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.api.time.TimeSource;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class TelemetryRunnableTest {

  private Thread thread;

  @AfterEach
  void cleanup() throws InterruptedException {
    if (thread != null && thread.isAlive()) {
      thread.interrupt();
      thread.join();
    }
  }

  @Test
  @WithConfig(key = "TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL", value = "65", env = true)
  void happyPath() throws InterruptedException, BrokenBarrierException, TimeoutException {
    TelemetryRunnable.ThreadSleeper sleeperMock = mock(TelemetryRunnable.ThreadSleeper.class);
    TickSleeper sleeper = new TickSleeper(sleeperMock);
    TimeSource timeSource = mock(TimeSource.class);
    TelemetryService telemetryService = mock(TelemetryService.class);
    MetricCollector<MetricCollector.Metric> metricCollector = mock(MetricCollector.class);
    MetricPeriodicAction metricAction = mock(MetricPeriodicAction.class);
    when(metricAction.collector()).thenReturn(metricCollector);
    TelemetryRunnable.TelemetryPeriodicAction periodicAction =
        mock(TelemetryRunnable.TelemetryPeriodicAction.class);
    TelemetryRunnable runnable =
        new TelemetryRunnable(
            telemetryService, asList(metricAction, periodicAction), sleeper, timeSource);
    thread = new Thread(runnable);

    // initial iteration before the first sleep (metrics and heartbeat)
    when(telemetryService.sendAppStartedEvent()).thenReturn(false, false, true);
    when(timeSource.getCurrentTimeMillis()).thenReturn(60L * 1000, 60L * 1000 + 1);
    when(metricCollector.drain()).thenReturn(asList());
    when(metricCollector.drainDistributionSeries()).thenReturn(asList());
    when(telemetryService.sendTelemetryEvents()).thenReturn(true, true, false);

    thread.start();
    sleeper.sleeped.await(10, TimeUnit.SECONDS);

    // two unsuccessful attempts to send app-started with the following successful attempt
    verify(telemetryService, times(3)).sendAppStartedEvent();
    verify(timeSource, times(2)).getCurrentTimeMillis();
    // two partial and one final telemetry data requests
    verify(metricCollector, times(1)).prepareMetrics();
    verify(metricCollector, times(1)).drain();
    verify(metricCollector, times(1)).drainDistributionSeries();
    verify(periodicAction, times(1)).doIteration(telemetryService);
    verify(telemetryService, times(3)).sendTelemetryEvents();
    verify(sleeperMock, times(1)).sleep(9999);
    verify(telemetryService, atLeast(0)).addConfiguration(any());
    verifyNoMoreInteractions(
        telemetryService, timeSource, sleeperMock, metricCollector, periodicAction);
    clearInvocations(telemetryService, timeSource, metricCollector, periodicAction, sleeperMock);

    // second iteration (10 seconds, metrics)
    when(timeSource.getCurrentTimeMillis()).thenReturn(70L * 1000, 70L * 1000 + 2);

    sleeper.go.await(10, TimeUnit.SECONDS);
    sleeper.sleeped.await(10, TimeUnit.SECONDS);

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(metricCollector, times(1)).prepareMetrics();
    verify(sleeperMock, times(1)).sleep(9998);
    verifyNoMoreInteractions(
        telemetryService, timeSource, sleeperMock, metricCollector, periodicAction);
    clearInvocations(telemetryService, timeSource, metricCollector, periodicAction, sleeperMock);

    // third iteration (20 seconds, metrics)
    when(timeSource.getCurrentTimeMillis()).thenReturn(80L * 1000, 80L * 1000 + 3);

    sleeper.go.await(10, TimeUnit.SECONDS);
    sleeper.sleeped.await(10, TimeUnit.SECONDS);

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(metricCollector, times(1)).prepareMetrics();
    verify(sleeperMock, times(1)).sleep(9997);
    verifyNoMoreInteractions(
        telemetryService, timeSource, sleeperMock, metricCollector, periodicAction);
    clearInvocations(telemetryService, timeSource, metricCollector, periodicAction, sleeperMock);

    // fourth iteration (30 seconds, metrics)
    when(timeSource.getCurrentTimeMillis()).thenReturn(90L * 1000, 90L * 1000 + 4);

    sleeper.go.await(10, TimeUnit.SECONDS);
    sleeper.sleeped.await(10, TimeUnit.SECONDS);

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(metricCollector, times(1)).prepareMetrics();
    verify(sleeperMock, times(1)).sleep(9996);
    verifyNoMoreInteractions(
        telemetryService, timeSource, sleeperMock, metricCollector, periodicAction);
    clearInvocations(telemetryService, timeSource, metricCollector, periodicAction, sleeperMock);

    // fifth iteration (40 seconds, metrics)
    when(timeSource.getCurrentTimeMillis()).thenReturn(100L * 1000, 100L * 1000 + 5);

    sleeper.go.await(10, TimeUnit.SECONDS);
    sleeper.sleeped.await(10, TimeUnit.SECONDS);

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(metricCollector, times(1)).prepareMetrics();
    verify(sleeperMock, times(1)).sleep(9995);
    verifyNoMoreInteractions(
        telemetryService, timeSource, sleeperMock, metricCollector, periodicAction);
    clearInvocations(telemetryService, timeSource, metricCollector, periodicAction, sleeperMock);

    // sixth iteration (50 seconds, metrics)
    when(timeSource.getCurrentTimeMillis()).thenReturn(110L * 1000, 110L * 1000 + 6);

    sleeper.go.await(10, TimeUnit.SECONDS);
    sleeper.sleeped.await(10, TimeUnit.SECONDS);

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(metricCollector, times(1)).prepareMetrics();
    verify(sleeperMock, times(1)).sleep(9994);
    verifyNoMoreInteractions(
        telemetryService, timeSource, sleeperMock, metricCollector, periodicAction);
    clearInvocations(telemetryService, timeSource, metricCollector, periodicAction, sleeperMock);

    // seventh iteration (60 seconds, metrics, heartbeat)
    when(timeSource.getCurrentTimeMillis()).thenReturn(120L * 1000, 120L * 1000 + 7);

    sleeper.go.await(10, TimeUnit.SECONDS);
    sleeper.sleeped.await(10, TimeUnit.SECONDS);

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(metricCollector, times(1)).prepareMetrics();
    verify(metricCollector, times(1)).drain();
    verify(metricCollector, times(1)).drainDistributionSeries();
    verify(periodicAction, times(1)).doIteration(telemetryService);
    verify(telemetryService, times(1)).sendTelemetryEvents();
    verify(sleeperMock, times(1)).sleep(9993);
    clearInvocations(telemetryService, timeSource, metricCollector, periodicAction, sleeperMock);

    // eighth iteration (65 seconds, extended-heartbeat)
    when(timeSource.getCurrentTimeMillis()).thenReturn(125L * 1000, 125L * 1000 + 8);

    sleeper.go.await(5, TimeUnit.SECONDS);
    sleeper.sleeped.await(5, TimeUnit.SECONDS);

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(telemetryService, times(1)).sendExtendedHeartbeat();
    verify(sleeperMock, times(1)).sleep(4992);
    verifyNoMoreInteractions(
        telemetryService, timeSource, sleeperMock, metricCollector, periodicAction);
    clearInvocations(telemetryService, timeSource, metricCollector, periodicAction, sleeperMock);

    thread.interrupt();
    thread.join();

    // flush pending data before shutdown
    verify(metricCollector, times(1)).prepareMetrics();
    verify(metricCollector, times(1)).drain();
    verify(metricCollector, times(1)).drainDistributionSeries();
    verify(periodicAction, times(1)).doIteration(telemetryService);
    verify(telemetryService, times(1)).sendTelemetryEvents();
    verify(telemetryService, times(1)).sendAppClosingEvent();
    verifyNoMoreInteractions(
        telemetryService, timeSource, sleeperMock, metricCollector, periodicAction);
  }

  @Test
  void doNotReattemptAppStartedEventUntilNextCycle()
      throws InterruptedException, BrokenBarrierException, TimeoutException {
    TelemetryRunnable.ThreadSleeper sleeperMock = mock(TelemetryRunnable.ThreadSleeper.class);
    TickSleeper sleeper = new TickSleeper(sleeperMock);
    TimeSource timeSource = mock(TimeSource.class);
    TelemetryService telemetryService = mock(TelemetryService.class);
    MetricCollector<MetricCollector.Metric> metricCollector = mock(MetricCollector.class);
    MetricPeriodicAction metricAction = mock(MetricPeriodicAction.class);
    when(metricAction.collector()).thenReturn(metricCollector);
    TelemetryRunnable.TelemetryPeriodicAction periodicAction =
        mock(TelemetryRunnable.TelemetryPeriodicAction.class);
    TelemetryRunnable runnable =
        new TelemetryRunnable(
            telemetryService, asList(metricAction, periodicAction), sleeper, timeSource);
    thread = new Thread(runnable);

    // three unsuccessful attempts to send app-started (TelemetryRunnable.MAX_APP_STARTED_RETRIES)
    when(telemetryService.sendAppStartedEvent()).thenReturn(false, false, false);
    when(timeSource.getCurrentTimeMillis()).thenReturn(60L * 1000);

    thread.start();
    sleeper.sleeped.await(10, TimeUnit.SECONDS);

    verify(telemetryService, times(3)).sendAppStartedEvent();
    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(sleeperMock, times(1)).sleep(10000);
  }

  @Test
  void schedulerSkipsMetricsIntervals() {
    TimeSource timeSource = mock(TimeSource.class);
    TelemetryRunnable.ThreadSleeper sleeper = mock(TelemetryRunnable.ThreadSleeper.class);
    TelemetryRunnable.Scheduler scheduler =
        new TelemetryRunnable.Scheduler(timeSource, sleeper, 60 * 1000, 10 * 1000, 0);

    // first iteration: run everything
    when(timeSource.getCurrentTimeMillis()).thenReturn(0L);

    scheduler.init();

    assertTrue(scheduler.shouldRunMetrics());
    assertTrue(scheduler.shouldRunHeartbeat());
    verify(timeSource, times(1)).getCurrentTimeMillis();
    verifyNoMoreInteractions(timeSource, sleeper);
    clearInvocations(timeSource, sleeper);

    when(timeSource.getCurrentTimeMillis()).thenReturn(1L, 10L * 1000);

    scheduler.sleepUntilNextIteration();

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(sleeper, times(1)).sleep(10 * 1000 - 1);
    verifyNoMoreInteractions(timeSource, sleeper);
    clearInvocations(timeSource, sleeper);

    // one metrics interval is exceeded
    assertTrue(scheduler.shouldRunMetrics());
    assertFalse(scheduler.shouldRunHeartbeat());

    when(timeSource.getCurrentTimeMillis()).thenReturn(20L * 1000 + 1, 30L * 1000);

    scheduler.sleepUntilNextIteration();

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(sleeper, times(1)).sleep(9999);
    verifyNoMoreInteractions(timeSource, sleeper);
    clearInvocations(timeSource, sleeper);

    // two metrics intervals are exceeded
    assertTrue(scheduler.shouldRunMetrics());
    assertFalse(scheduler.shouldRunHeartbeat());

    when(timeSource.getCurrentTimeMillis()).thenReturn(50L * 1000 + 2, 60L * 1000);

    scheduler.sleepUntilNextIteration();

    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(sleeper, times(1)).sleep(9998);
    verifyNoMoreInteractions(timeSource, sleeper);
    assertTrue(scheduler.shouldRunMetrics());
    assertTrue(scheduler.shouldRunHeartbeat());
  }

  @Test
  void schedulerSkipsHeartbeatIntervals() {
    TimeSource timeSource = mock(TimeSource.class);
    TelemetryRunnable.ThreadSleeper sleeper = mock(TelemetryRunnable.ThreadSleeper.class);
    TelemetryRunnable.Scheduler scheduler =
        new TelemetryRunnable.Scheduler(timeSource, sleeper, 60 * 1000, 10 * 1000, 0);

    // first iteration
    when(timeSource.getCurrentTimeMillis()).thenReturn(0L);
    scheduler.init();

    // run everything
    assertTrue(scheduler.shouldRunMetrics());
    assertTrue(scheduler.shouldRunHeartbeat());
    verify(timeSource, times(1)).getCurrentTimeMillis();
    verifyNoMoreInteractions(timeSource, sleeper);
    clearInvocations(timeSource, sleeper);

    // when
    when(timeSource.getCurrentTimeMillis()).thenReturn(1L, 10L * 1000);
    scheduler.sleepUntilNextIteration();

    // then
    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(sleeper, times(1)).sleep(10 * 1000 - 1);
    verifyNoMoreInteractions(timeSource, sleeper);
    clearInvocations(timeSource, sleeper);

    // when heartbeat interval is exceeded
    assertTrue(scheduler.shouldRunMetrics());
    assertFalse(scheduler.shouldRunHeartbeat());
    when(timeSource.getCurrentTimeMillis()).thenReturn(70L * 1000);
    scheduler.sleepUntilNextIteration();

    // then
    verify(timeSource, times(1)).getCurrentTimeMillis();
    verifyNoMoreInteractions(timeSource, sleeper);
    assertTrue(scheduler.shouldRunMetrics());
    assertTrue(scheduler.shouldRunHeartbeat());
    clearInvocations(timeSource, sleeper);

    // when metrics interval has been adjusted
    when(timeSource.getCurrentTimeMillis()).thenReturn(70L * 1000 + 1, 80L * 1000);
    scheduler.sleepUntilNextIteration();

    // then
    verify(timeSource, times(2)).getCurrentTimeMillis();
    verify(sleeper, times(1)).sleep(10 * 1000 - 1);
    verifyNoMoreInteractions(timeSource, sleeper);
    assertTrue(scheduler.shouldRunMetrics());
    assertFalse(scheduler.shouldRunHeartbeat());
  }

  @TableTest({
    "scenario                                              | iters | metricsSecs | heartbeatSecs | extHeartbeatSecs | expectedMetrics | expectedHeartbeats | expectedExtHeartbeats",
    "no intervals configured                               | 10    | 0           | 0             | 0                | 10              | 10                 | 10                   ",
    "one second intervals                                  | 10    | 1           | 1             | 1                | 10              | 10                 | 9                    ",
    "metrics runs more frequently than heartbeat           | 12    | 10          | 60            | 60               | 12              | 2                  | 1                    ",
    "heartbeat runs more frequently than metrics           | 12    | 60          | 10            | 10               | 2               | 12                 | 11                   ",
    "metrics and heartbeat intervals close together (3, 5) | 6     | 3           | 5             | 5                | 4               | 3                  | 2                    ",
    "metrics and heartbeat intervals close together (5, 3) | 6     | 5           | 3             | 3                | 3               | 4                  | 3                    "
  })
  void schedulerWithHeartbeatMetricsAndExtendedHeartbeatIntervals(
      int iters,
      int metricsSecs,
      int heartbeatSecs,
      int extHeartbeatSecs,
      int expectedMetrics,
      int expectedHeartbeats,
      int expectedExtHeartbeats) {
    TimeSourceAndSleeper timing = new TimeSourceAndSleeper();
    TelemetryRunnable.Scheduler scheduler =
        new TelemetryRunnable.Scheduler(
            timing, timing, heartbeatSecs * 1000L, metricsSecs * 1000L, extHeartbeatSecs * 1000L);
    int metricsRunCount = 0;
    int heartbeatsRunCount = 0;
    int extHeartbeatsRunCount = 0;

    scheduler.init();
    for (int i = 0; i < iters; i++) {
      if (scheduler.shouldRunMetrics()) {
        metricsRunCount++;
      }
      if (scheduler.shouldRunHeartbeat()) {
        heartbeatsRunCount++;
      }
      boolean runExtHeartbeat = scheduler.shouldRunExtendedHeartbeat();
      if (runExtHeartbeat) {
        extHeartbeatsRunCount++;
        // need to manually advance to retry next iteration if extended-heartbeat request failed
        scheduler.scheduleNextExtendedHeartbeat();
      }
      scheduler.sleepUntilNextIteration();
    }

    assertEquals(expectedMetrics, metricsRunCount);
    assertEquals(expectedHeartbeats, heartbeatsRunCount);
    assertEquals(expectedExtHeartbeats, extHeartbeatsRunCount);
  }

  // wraps a ThreadSleeper delegate with two barriers so the test thread can step the background
  // runnable one iteration at a time
  private static final class TickSleeper implements TelemetryRunnable.ThreadSleeper {
    final CyclicBarrier sleeped = new CyclicBarrier(2);
    final CyclicBarrier go = new CyclicBarrier(2);
    final TelemetryRunnable.ThreadSleeper delegate;

    TickSleeper(TelemetryRunnable.ThreadSleeper delegate) {
      this.delegate = delegate;
    }

    @Override
    public void sleep(long timeoutMs) {
      if (delegate != null) {
        delegate.sleep(timeoutMs);
      }
      try {
        sleeped.await(10, TimeUnit.SECONDS);
        go.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
        // Thread.interrupt() on the background thread trips this barrier; rethrow the real
        // exception unchecked so it reaches TelemetryRunnable.run()'s catch (InterruptedException)
        // block exactly like the interrupted CyclicBarrier.await() would in the JVM.
        sneakyThrow(e);
      }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
      throw (T) t;
    }
  }

  private static final class TimeSourceAndSleeper
      implements TimeSource, TelemetryRunnable.ThreadSleeper {

    private long currentTime = 0;

    @Override
    public void sleep(long timeoutMs) {
      currentTime += timeoutMs;
    }

    @Override
    public long getCurrentTimeMillis() {
      return currentTime;
    }

    @Override
    public long getNanoTicks() {
      throw new UnsupportedOperationException("NOT IMPLEMENTED");
    }

    @Override
    public long getCurrentTimeMicros() {
      throw new UnsupportedOperationException("NOT IMPLEMENTED");
    }

    @Override
    public long getCurrentTimeNanos() {
      throw new UnsupportedOperationException("NOT IMPLEMENTED");
    }
  }
}
