package datadog.trace.api.sampling;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class PerRecordingRateLimiter {

  private final AdaptiveSampler sampler;

  public PerRecordingRateLimiter(Duration windowDuration, int limit, Duration recordingLength) {
    this(windowDuration, limit, recordingLength, 16);
  }

  public PerRecordingRateLimiter(
      Duration windowDuration, int limit, Duration recordingLength, int budgetLookback) {
    int lookback = samplingWindowsPerRecording(recordingLength.getSeconds(), windowDuration);
    int samplesPerWindow =
        limit / samplingWindowsPerRecording(recordingLength.getSeconds(), windowDuration);
    sampler = new AdaptiveSampler(windowDuration, samplesPerWindow, lookback, budgetLookback, true);
  }

  public boolean permit() {
    return sampler.sample();
  }

  public static int samplingWindowsPerRecording(long uploadPeriodSeconds, Duration samplingWindow) {
    /*
     * Java8 doesn't have dividedBy#Duration so we have to implement poor man's version.
     * None of these durations should be big enough to warrant dealing with bigints.
     * We also do not care about nanoseconds here.
     */
    return (int)
        Math.min(
            Duration.of(uploadPeriodSeconds, ChronoUnit.SECONDS).toMillis()
                / samplingWindow.toMillis(),
            Integer.MAX_VALUE);
  }
}
