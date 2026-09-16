package datadog.trace.bootstrap.debugger.util;

import datadog.trace.api.Config;
import java.time.Duration;

public interface TimeoutChecker {

  String CPU = "CPU";
  String WALL = "WALL";

  boolean isTimedOut();

  Duration getTimeOut();

  static TimeoutChecker create(Config config, Duration timeout) {
    if (config.getDynamicInstrumentationTimeoutCheckerMode().equals(CPU)) {
      return new CpuTimeoutChecker(timeout);
    }
    return new WallTimeoutChecker(timeout);
  }
}
