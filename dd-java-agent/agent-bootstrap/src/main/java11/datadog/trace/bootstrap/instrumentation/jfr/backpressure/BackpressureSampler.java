package datadog.trace.bootstrap.instrumentation.jfr.backpressure;

import static datadog.trace.api.sampling.PerRecordingRateLimiter.samplingWindowsPerRecording;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.jfr.WindowSampler;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

final class BackpressureSampler extends WindowSampler<BackpressureSampleEvent> {
  /*
   * Fixed 0.5 second sampling window.
   * Logic in AdaptiveSampler relies on sampling window being small compared to (in our case) recording duration:
   * sampler may overshoot on one given window but should average to samplesPerWindow in the long run.
   */
  private static final Duration SAMPLING_WINDOW = Duration.of(500, ChronoUnit.MILLIS);

  BackpressureSampler(final Config config) {
    this(
        SAMPLING_WINDOW,
        getSamplesPerWindow(config),
        samplingWindowsPerRecording(config.getProfilingUploadPeriod(), SAMPLING_WINDOW));
  }

  BackpressureSampler(Duration windowDuration, int samplesPerWindow, int lookback) {
    super(windowDuration, samplesPerWindow, lookback, BackpressureSampleEvent.class);
  }

  protected static int getSamplesPerWindow(final Config config) {
    return config.getProfilingBackPressureSampleLimit()
        / samplingWindowsPerRecording(config.getProfilingUploadPeriod(), SAMPLING_WINDOW);
  }
}
