package datadog.trace.bootstrap.instrumentation.jfr;

import datadog.trace.api.sampling.AdaptiveSampler;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import jdk.jfr.Event;
import jdk.jfr.EventType;

public class WindowSampler<E extends Event> {

  private final AdaptiveSampler sampler;
  private final EventType sampleType;

  protected WindowSampler(
      Duration windowDuration, int samplesPerWindow, int lookback, Class<E> eventType) {
    sampler = new AdaptiveSampler(windowDuration, samplesPerWindow, lookback, 16);
    sampleType = EventType.getEventType(eventType);
  }

  public boolean sample() {
    return sampleType.isEnabled() && sampler.sample();
  }

  protected static int samplingWindowsPerRecording(
      long uploadPeriodSeconds, Duration samplingWindow) {
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
