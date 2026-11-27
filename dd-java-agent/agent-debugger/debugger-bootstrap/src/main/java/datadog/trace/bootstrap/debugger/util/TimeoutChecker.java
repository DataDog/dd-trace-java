package datadog.trace.bootstrap.debugger.util;

import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import java.time.Duration;

public interface TimeoutChecker {

  String CPU = "CPU";
  String WALL = "WALL";

  /**
   * Wall-clock mode compensating JVM pauses detected by {@link PauseDetector}. Falls back to {@link
   * #WALL} on Windows, where the coarse timer resolution (~15.6ms) makes almost every detector
   * sleep look like a pause, or when the detector is not started (see {@link
   * #startPauseDetectorIfEnabled(Config)}).
   */
  String WALL_PAUSE_DETECTOR = "WALL-PAUSE-DETECTOR";

  boolean isTimedOut();

  Duration getTimeOut();

  static TimeoutChecker create(Config config, Duration timeout) {
    String mode = config.getDynamicInstrumentationTimeoutCheckerMode();
    if (CPU.equals(mode)) {
      return new CpuTimeoutChecker(timeout);
    }
    if (WALL_PAUSE_DETECTOR.equals(mode)) {
      // null when the detector is not started (Windows, start failure, not initialized yet)
      PauseDetector pauseDetector = PauseDetector.get();
      if (pauseDetector != null) {
        return new WallTimeoutChecker(timeout, pauseDetector);
      }
    }
    return new WallTimeoutChecker(timeout);
  }

  /**
   * Starts the shared {@link PauseDetector} when the {@link #WALL_PAUSE_DETECTOR} mode is
   * configured. Must be called from an agent thread so the detector thread does not inherit
   * application thread locals.
   */
  static void startPauseDetectorIfEnabled(Config config) {
    if (WALL_PAUSE_DETECTOR.equals(config.getDynamicInstrumentationTimeoutCheckerMode())
        && !OperatingSystem.isWindows()) {
      PauseDetector.start();
    }
  }
}
