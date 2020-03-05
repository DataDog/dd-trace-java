package datadog.trace.bootstrap.instrumentation.jfr.exceptions;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.AdaptiveIntervalSampler;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/**
 * JVM-wide singleton exception event sampler. Uses {@linkplain Config} class to configure a
 * {@linkplain AdaptiveIntervalSampler} instance using either system properties, environment or
 * properties override.
 */
public final class ExceptionEventSampler {
  private static final int TIME_WINDOW_SECS;
  private static final int MAX_WINDOW_SAMPLES;

  private static final AdaptiveIntervalSampler SAMPLER;
  private static final int SAMPLER_INTERVAL;
  private static final EventType EXCEPTION_SAMPLE_EVENT_TYPE;

  static {
    Config cfg = Config.get();
    SAMPLER_INTERVAL = cfg.getProfilingExceptionSamplerInterval();
    TIME_WINDOW_SECS = cfg.getProfilingExceptionSamplerTimeWindow();
    MAX_WINDOW_SAMPLES = cfg.getProfilingExceptionSamplerMaxSamples();
    SAMPLER =
        new AdaptiveIntervalSampler(
            "exceptions", SAMPLER_INTERVAL, TIME_WINDOW_SECS * 1000, MAX_WINDOW_SAMPLES);
    EXCEPTION_SAMPLE_EVENT_TYPE = EventType.getEventType(ExceptionSampleEvent.class);

    FlightRecorderListener listener =
        new FlightRecorderListener() {
          @Override
          public void recordingStateChanged(Recording recording) {
            if (recording.getState() == RecordingState.STOPPED) {
              SAMPLER.reset();
            }
          }
        };
    FlightRecorder.addListener(listener);
  }

  public static ExceptionSampleEvent sample(Exception e) {
    if (EXCEPTION_SAMPLE_EVENT_TYPE.isEnabled() && SAMPLER.sample()) {
      return new ExceptionSampleEvent(e);
    }
    return null;
  }

  public static int getInterval() {
    return SAMPLER_INTERVAL;
  }

  public static int getMaxWindowSamples() {
    return MAX_WINDOW_SAMPLES;
  }

  public static long getTimeWindowMs() {
    return TIME_WINDOW_SECS * 1000;
  }
}
