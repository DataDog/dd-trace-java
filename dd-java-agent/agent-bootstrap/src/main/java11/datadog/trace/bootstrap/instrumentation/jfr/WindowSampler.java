package datadog.trace.bootstrap.instrumentation.jfr;

import datadog.trace.api.sampling.AdaptiveSampler;
import java.time.Duration;
import jdk.jfr.Event;
import jdk.jfr.EventType;

public class WindowSampler<E extends Event> {

  private final AdaptiveSampler sampler;
  private final EventType sampleType;

  protected WindowSampler(
      Duration windowDuration, int samplesPerWindow, int lookback, Class<E> eventType) {
    sampler = new AdaptiveSampler(windowDuration, samplesPerWindow, lookback, 16, false);
    sampleType = EventType.getEventType(eventType);
  }

  public void start() {
    sampler.start();
  }

  public boolean sample() {
    return sampleType.isEnabled() && sampler.sample();
  }
}
