package datadog.trace.bootstrap.debugger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

public class TimeoutCheckerTest {
  @Test
  public void wallTimedOut() {
    long start = System.currentTimeMillis();
    Duration timeout = Duration.ofMillis(100);
    // assume that start & timestamp captured in instance below are very close
    WallTimeoutChecker timeoutChecker = new WallTimeoutChecker(timeout);
    assertFalse(timeoutChecker.isTimedOut(start + 1));
    assertTrue(timeoutChecker.isTimedOut(start + timeout.toMillis() * 2));
    timeoutChecker = new WallTimeoutChecker(start, timeout);
    assertFalse(timeoutChecker.isTimedOut(start + 1));
    assertTrue(timeoutChecker.isTimedOut(start + timeout.toMillis() * 2));
  }

  @Test
  public void cpuTimedOut() {
    CpuTimeoutChecker cpuTimeoutChecker = new CpuTimeoutChecker(Duration.ofMillis(1));
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); // not consume cpu
    assertFalse(cpuTimeoutChecker.isTimedOut());
    burnCpu(5_000_000);
    assertTrue(cpuTimeoutChecker.isTimedOut());
  }

  @Test
  public void wallNotTimedOutDuringFakeGcPause() throws Exception {
    // a fake stop-the-world pause: while the lock is held, the detector thread blocks at its next
    // "safepoint" poll after each sleep
    ReentrantLock detectorSafepoint = new ReentrantLock();
    PauseDetector pauseDetector =
        new PauseDetector(millis -> {
          Thread.sleep(millis);
          detectorSafepoint.lock();
          detectorSafepoint.unlock();
        });
    Thread detectorThread = new Thread(pauseDetector::run, "test-pause-detector");
    detectorThread.setDaemon(true);
    detectorThread.start();
    Duration wallTimeout = Duration.ofMillis(50);
    long fakeGcPauseMs = 200;
    CountDownLatch checkersCreated = new CountDownLatch(1);
    CountDownLatch appResumed = new CountDownLatch(1);
    AtomicBoolean compensatedTimedOut = new AtomicBoolean(true);
    AtomicBoolean uncompensatedTimedOut = new AtomicBoolean(false);
    AtomicLong elapsedMs = new AtomicLong();
    Thread appThread =
        new Thread(
            () -> {
              WallTimeoutChecker compensated = new WallTimeoutChecker(wallTimeout, pauseDetector);
              WallTimeoutChecker uncompensated = new WallTimeoutChecker(wallTimeout, null);
              checkersCreated.countDown();
              try {
                // the app thread is stopped for the whole fake GC pause
                appResumed.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              long now = System.currentTimeMillis();
              elapsedMs.set(now - compensated.getStart());
              compensatedTimedOut.set(compensated.isTimedOut(now));
              uncompensatedTimedOut.set(uncompensated.isTimedOut(now));
            },
            "test-app");
    try {
      long startPauseMs = pauseDetector.getTotalPauseMs();
      detectorSafepoint.lock();
      try {
        appThread.start();
        assertTrue(checkersCreated.await(5, TimeUnit.SECONDS));
        Thread.sleep(fakeGcPauseMs);
      } finally {
        detectorSafepoint.unlock();
      }
      // resume the detector before the app thread so the pause is accounted deterministically
      waitForPauseDetected(pauseDetector, startPauseMs);
      long detectedPauseMs = pauseDetector.getTotalPauseMs() - startPauseMs;
      assertTrue(
          detectedPauseMs >= fakeGcPauseMs - wallTimeout.toMillis(),
          "detected pause " + detectedPauseMs + "ms should be close to " + fakeGcPauseMs + "ms");
      appResumed.countDown();
      appThread.join(TimeUnit.SECONDS.toMillis(5));
    } finally {
      appResumed.countDown();
      detectorThread.interrupt();
    }
    assertTrue(elapsedMs.get() >= fakeGcPauseMs);
    assertTrue(uncompensatedTimedOut.get());
    assertFalse(compensatedTimedOut.get());
  }

  @Test
  public void pauseDetectorAccumulatesSkew() {
    PauseDetector pauseDetector = new PauseDetector();
    pauseDetector.onSleep(PauseDetector.SLEEP_MS);
    pauseDetector.onSleep(PauseDetector.SLEEP_MS + PauseDetector.SKEW_THRESHOLD_MS - 1);
    assertEquals(0, pauseDetector.getTotalPauseMs());
    pauseDetector.onSleep(PauseDetector.SLEEP_MS + PauseDetector.SKEW_THRESHOLD_MS);
    assertEquals(PauseDetector.SKEW_THRESHOLD_MS, pauseDetector.getTotalPauseMs());
    pauseDetector.onSleep(PauseDetector.SLEEP_MS + 100);
    assertEquals(PauseDetector.SKEW_THRESHOLD_MS + 100, pauseDetector.getTotalPauseMs());
  }

  @Test
  public void wallCompensatesPauses() {
    PauseDetector pauseDetector = new PauseDetector();
    // pauses before the checker creation are not compensated
    pauseDetector.onSleep(PauseDetector.SLEEP_MS + 1000);
    long start = 1_000_000;
    WallTimeoutChecker timeoutChecker =
        new WallTimeoutChecker(start, Duration.ofMillis(100), pauseDetector);
    assertTrue(timeoutChecker.isTimedOut(start + 150));
    pauseDetector.onSleep(PauseDetector.SLEEP_MS + 80);
    // 150ms elapsed - 80ms paused = 70ms
    assertFalse(timeoutChecker.isTimedOut(start + 150));
    // 190ms elapsed - 80ms paused = 110ms
    assertTrue(timeoutChecker.isTimedOut(start + 190));
  }

  private static void waitForPauseDetected(PauseDetector pauseDetector, long startPauseMs) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (pauseDetector.getTotalPauseMs() == startPauseMs && System.nanoTime() < deadline) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
  }

  static volatile int counter;

  private static void burnCpu(int iterations) {
    for (int i = 0; i < iterations; i++) {
      counter++;
    }
  }

  @Test
  public void configTimeout() {
    Config config = mock(Config.class);
    when(config.getDynamicInstrumentationTimeoutCheckerMode()).thenReturn(TimeoutChecker.CPU);
    assertInstanceOf(CpuTimeoutChecker.class, TimeoutChecker.create(config, Duration.ofMillis(50)));
    when(config.getDynamicInstrumentationTimeoutCheckerMode()).thenReturn(TimeoutChecker.WALL);
    TimeoutChecker wall = TimeoutChecker.create(config, Duration.ofMillis(50));
    assertInstanceOf(WallTimeoutChecker.class, wall);
    assertFalse(((WallTimeoutChecker) wall).isCompensatingPauses());
    when(config.getDynamicInstrumentationTimeoutCheckerMode())
        .thenReturn(TimeoutChecker.WALL_PAUSE_DETECTOR);
    TimeoutChecker wallPauseDetector = TimeoutChecker.create(config, Duration.ofMillis(50));
    assertInstanceOf(WallTimeoutChecker.class, wallPauseDetector);
    // pause detector is disabled on Windows because of the coarse timer resolution
    assertEquals(
        !OperatingSystem.isWindows(),
        ((WallTimeoutChecker) wallPauseDetector).isCompensatingPauses());
  }

  @Test
  public void wallWithoutPauseDetectorDoesNotCompensate() {
    long start = 1_000_000;
    WallTimeoutChecker timeoutChecker = new WallTimeoutChecker(start, Duration.ofMillis(100), null);
    assertFalse(timeoutChecker.isCompensatingPauses());
    assertFalse(timeoutChecker.isTimedOut(start + 100));
    assertTrue(timeoutChecker.isTimedOut(start + 150));
  }
}
