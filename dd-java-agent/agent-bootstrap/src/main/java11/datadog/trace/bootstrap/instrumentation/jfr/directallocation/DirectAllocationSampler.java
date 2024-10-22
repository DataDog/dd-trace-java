package datadog.trace.bootstrap.instrumentation.jfr.directallocation;

import static datadog.trace.api.sampling.PerRecordingRateLimiter.samplingWindowsPerRecording;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.jfr.WindowSampler;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class DirectAllocationSampler extends WindowSampler<DirectAllocationSampleEvent> {

  /*
   * Fixed 0.5 second sampling window.
   * Logic in AdaptiveSampler relies on sampling window being small compared to (in our case) recording duration:
   * sampler may overshoot on one given window but should average to samplesPerWindow in the long run.
   */
  private static final Duration SAMPLING_WINDOW = Duration.of(500, ChronoUnit.MILLIS);

  protected DirectAllocationSampler(Config conf) {
    super(
        SAMPLING_WINDOW,
        getSamplesPerWindow(conf),
        samplingWindowsPerRecording(conf.getProfilingUploadPeriod(), SAMPLING_WINDOW),
        DirectAllocationSampleEvent.class);
  }

  protected static int getSamplesPerWindow(final Config config) {
    return config.getProfilingDirectAllocationSampleLimit()
        / samplingWindowsPerRecording(config.getProfilingUploadPeriod(), SAMPLING_WINDOW);
  }
}
