package datadog.trace.bootstrap.debugger;

import datadog.trace.api.sampling.AdaptiveSampler;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Rate limiter for sending snapshot to backend Use a global rate limiter and one per probe */
public class ProbeRateLimiter {
  public static final double DEFAULT_SNAPSHOT_RATE = 1.0;
  public static final double DEFAULT_LOG_RATE = 5000.0;
  private static final Duration ONE_SECOND_WINDOW = Duration.of(1, ChronoUnit.SECONDS);
  private static final Duration TEN_SECONDS_WINDOW = Duration.of(10, ChronoUnit.SECONDS);
  private static final double DEFAULT_GLOBAL_SNAPSHOT_RATE = DEFAULT_SNAPSHOT_RATE * 100;
  private static final double DEFAULT_GLOBAL_LOG_RATE = 5000.0;
  private static final ConcurrentMap<String, RateLimitInfo> PROBE_SAMPLERS =
      new ConcurrentHashMap<>();
  private static AdaptiveSampler GLOBAL_SNAPSHOT_SAMPLER =
      createSampler(DEFAULT_GLOBAL_SNAPSHOT_RATE);
  private static AdaptiveSampler GLOBAL_LOG_SAMPLER = createSampler(DEFAULT_GLOBAL_LOG_RATE);

  public static boolean tryProbe(String probeId) {
    RateLimitInfo rateLimitInfo =
        PROBE_SAMPLERS.computeIfAbsent(
            probeId, k -> new RateLimitInfo(createSampler(DEFAULT_SNAPSHOT_RATE), true));
    AdaptiveSampler globalSampler =
        rateLimitInfo.isCaptureSnapshot ? GLOBAL_SNAPSHOT_SAMPLER : GLOBAL_LOG_SAMPLER;
    if (globalSampler.sample()) {
      return rateLimitInfo.sampler.sample();
    }
    return false;
  }

  public static void setRate(String probeId, double rate, boolean isCaptureSnapshot) {
    PROBE_SAMPLERS.put(probeId, new RateLimitInfo(createSampler(rate), isCaptureSnapshot));
  }

  public static void setGlobalSnapshotRate(double rate) {
    GLOBAL_SNAPSHOT_SAMPLER = createSampler(rate);
  }

  public static void setGlobalLogRate(double rate) {
    GLOBAL_LOG_SAMPLER = createSampler(rate);
  }

  public static void resetRate(String probeId) {
    PROBE_SAMPLERS.remove(probeId);
  }

  public static void resetGlobalRate() {
    setGlobalSnapshotRate(DEFAULT_GLOBAL_LOG_RATE);
  }

  public static void resetAll() {
    PROBE_SAMPLERS.clear();
    resetGlobalRate();
  }

  private static AdaptiveSampler createSampler(double rate) {
    if (rate < 1) {
      int intRate = (int) Math.round(rate * 10);
      return new AdaptiveSampler(TEN_SECONDS_WINDOW, intRate, 180, 16);
    }
    return new AdaptiveSampler(ONE_SECOND_WINDOW, (int) Math.round(rate), 180, 16);
  }

  private static class RateLimitInfo {
    final AdaptiveSampler sampler;
    final boolean isCaptureSnapshot;

    public RateLimitInfo(AdaptiveSampler sampler, boolean isCaptureSnapshot) {
      this.sampler = sampler;
      this.isCaptureSnapshot = isCaptureSnapshot;
    }
  }
}
