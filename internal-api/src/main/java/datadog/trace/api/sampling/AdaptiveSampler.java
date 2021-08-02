package datadog.trace.api.sampling;

import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An adaptive streaming (non-remembering) sampler.
 *
 * <p>The sampler attempts to generate at most N samples per fixed time window in randomized
 * fashion. For this it divides the timeline into 'sampling windows' of constant duration. Each
 * sampling window targets a constant number of samples which are scattered randomly (uniform
 * distribution) throughout the window duration and once the window is over the real stats of
 * incoming events and the number of gathered samples is used to recompute the target probability to
 * use in the following window.
 *
 * <p>This will guarantee, if the windows are not excessively large, that the sampler will be able
 * to adjust to the changes in the rate of incoming events.
 *
 * <p>However, there might so rapid changes in incoming events rate that we will optimistically use
 * all allowed samples well before the current window has elapsed or, on the other end of the
 * spectrum, there will be to few incoming events and the sampler will not be able to generate the
 * target number of samples.
 *
 * <p>To smooth out these hiccups the sampler maintains an under-sampling budget which can be used
 * to compensate for too rapid changes in the incoming events rate and maintain the target average
 * number of samples per window.
 */
public abstract class AdaptiveSampler {
  private static final Logger log = LoggerFactory.getLogger(AdaptiveSampler.class);

  private static final Constructor<? extends AdaptiveSampler> CONSTRUCTOR;

  static {
    Class<? extends AdaptiveSampler> samplerClass = null;
    Constructor<? extends AdaptiveSampler> constructor = null;
    try {
      ClassLoader cLoader = AdaptiveSampler.class.getClassLoader();
      cLoader.loadClass("java.util.Stream");
      samplerClass =
          (Class<? extends AdaptiveSampler>)
              cLoader.loadClass("datadog.trace.api.sampling.AdaptiveSampler8");
      constructor = samplerClass.getConstructor(long.class, TimeUnit.class, int.class, int.class);
    } catch (Throwable ignored) {
      // optimized, JDK 8+ only version not available
    }
    CONSTRUCTOR = constructor;
  }

  /** Use {@linkplain AdaptiveSampler#instance(long, TimeUnit, int, int)} instead */
  protected AdaptiveSampler() {}

  /**
   * Create a new {@linkplain AdaptiveSampler instance}
   *
   * @param windowDuration the sampling window duration
   * @param windowDurationUnit the sampling window duration unit
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param lookback the number of windows to consider in averaging the sampling rate
   * @return a runtime optimized instane of {@linkplain AdaptiveSampler}
   */
  public static AdaptiveSampler instance(
      long windowDuration, TimeUnit windowDurationUnit, int samplesPerWindow, int lookback) {
    if (CONSTRUCTOR != null) {
      try {
        return CONSTRUCTOR.newInstance(
            windowDuration, windowDurationUnit, samplesPerWindow, lookback);
      } catch (Throwable t) {
        if (log.isDebugEnabled()) {
          log.warn("Unable to create JDK 8 optimized instance of AdaptiveSampler.");
        }
      }
    }
    return new AdaptiveSampler7(windowDuration, windowDurationUnit, samplesPerWindow, lookback);
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  public abstract boolean sample();

  public abstract boolean keep();

  public abstract boolean drop();
}
