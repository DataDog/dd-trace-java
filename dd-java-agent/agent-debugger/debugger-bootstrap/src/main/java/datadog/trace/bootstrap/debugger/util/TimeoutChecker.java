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
   * sleep look like a pause, or when the detector thread cannot be started.
   */
  String WALL_PAUSE_DETECTOR = "WALL-PAUSE-DETECTOR";

  boolean isTimedOut();

  Duration getTimeOut();

  static TimeoutChecker create(Config config, Duration timeout) {
    String mode = config.getDynamicInstrumentationTimeoutCheckerMode();
    if (CPU.equals(mode)) {
      return new CpuTimeoutChecker(timeout);
    }
    if (WALL_PAUSE_DETECTOR.equals(mode) && !OperatingSystem.isWindows()) {
      try {
        return new WallTimeoutChecker(timeout, PauseDetector.get());
      } catch (Throwable t) {
        // the detector thread could not be started (e.g. no more native threads): its class
        // initialization failed, fall back to the uncompensated wall-clock checker
      }
    }
    return new WallTimeoutChecker(timeout);
  }
}
