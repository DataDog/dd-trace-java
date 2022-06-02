package datadog.trace.bootstrap.debugger;

import datadog.trace.api.sampling.AdaptiveSampler;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Rate limiter for sending snapshot to backend Use a global rate limiter and one per probe */
public final class ProbeRateLimiter {
  private static final Duration ONE_SECOND_WINDOW = Duration.of(1, ChronoUnit.SECONDS);
  private static final Duration TEN_SECONDS_WINDOW = Duration.of(10, ChronoUnit.SECONDS);
  private static final double DEFAULT_RATE = 1.0;
  private static final double DEFAULT_GLOBAL_RATE = DEFAULT_RATE * 100;
  private static final ConcurrentMap<String, AdaptiveSampler> PROBE_SAMPLERS =
      new ConcurrentHashMap<>();

  private static AdaptiveSampler GLOBAL_SAMPLER = createSampler(DEFAULT_GLOBAL_RATE);

  public static boolean tryProbe(String probeId) {
    // rate limiter engaged at ~1 probe per second (1 probes per 1s time window)
    boolean result =
        PROBE_SAMPLERS.computeIfAbsent(probeId, k -> createSampler(DEFAULT_RATE)).sample();
    result &= GLOBAL_SAMPLER.sample();
    return result;
  }

  public static void setRate(String probeId, double rate) {
    PROBE_SAMPLERS.put(probeId, createSampler(rate));
  }

  public static void setGlobalRate(double rate) {
    GLOBAL_SAMPLER = createSampler(rate);
  }

  public static void resetRate(String probeId) {
    PROBE_SAMPLERS.remove(probeId);
  }

  public static void resetGlobalRate() {
    setGlobalRate(DEFAULT_GLOBAL_RATE);
  }

  private static AdaptiveSampler createSampler(double rate) {
    if (rate < 1) {
      int intRate = (int) Math.round(rate * 10);
      return new AdaptiveSampler(TEN_SECONDS_WINDOW, intRate, 180, 16);
    }
    return new AdaptiveSampler(ONE_SECOND_WINDOW, (int) Math.round(rate), 180, 16);
  }
}
