package datadog.trace.bootstrap.debugger;

import datadog.trace.api.sampling.AdaptiveSampler;
import datadog.trace.api.sampling.ConstantSampler;
import datadog.trace.api.sampling.Sampler;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.DoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Rate limiter for sending snapshot to backend Use a global rate limiter and one per probe */
public class ProbeRateLimiter {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProbeRateLimiter.class);
  public static final double DEFAULT_SNAPSHOT_RATE = 1.0;
  public static final double DEFAULT_LOG_RATE = 5000.0;
  private static final Duration ONE_SECOND_WINDOW = Duration.of(1, ChronoUnit.SECONDS);
  private static final Duration TEN_SECONDS_WINDOW = Duration.of(10, ChronoUnit.SECONDS);
  private static final double DEFAULT_GLOBAL_SNAPSHOT_RATE = DEFAULT_SNAPSHOT_RATE * 100;
  private static final double DEFAULT_GLOBAL_LOG_RATE = 5000.0;
  private static final ConcurrentMap<String, RateLimitInfo> PROBE_SAMPLERS =
      new ConcurrentHashMap<>();
  private static Sampler GLOBAL_SNAPSHOT_SAMPLER = createSampler(DEFAULT_GLOBAL_SNAPSHOT_RATE);
  private static Sampler GLOBAL_LOG_SAMPLER = createSampler(DEFAULT_GLOBAL_LOG_RATE);
  private static DoubleFunction<Sampler> samplerSupplier = ProbeRateLimiter::createSampler;

  public static boolean tryProbe(String probeId) {
    RateLimitInfo rateLimitInfo =
        PROBE_SAMPLERS.computeIfAbsent(probeId, ProbeRateLimiter::getDefaultRateLimitInfo);
    Sampler globalSampler =
        rateLimitInfo.isCaptureSnapshot ? GLOBAL_SNAPSHOT_SAMPLER : GLOBAL_LOG_SAMPLER;
    if (globalSampler.sample()) {
      return rateLimitInfo.sampler.sample();
    }
    return false;
  }

  private static RateLimitInfo getDefaultRateLimitInfo(String probeId) {
    LOGGER.debug("Setting sampling with default snapshot rate for probeId={}", probeId);
    return new RateLimitInfo(samplerSupplier.apply(DEFAULT_SNAPSHOT_RATE), true);
  }

  public static void setRate(String probeId, double rate, boolean isCaptureSnapshot) {
    PROBE_SAMPLERS.put(probeId, new RateLimitInfo(samplerSupplier.apply(rate), isCaptureSnapshot));
  }

  public static void setGlobalSnapshotRate(double rate) {
    GLOBAL_SNAPSHOT_SAMPLER = samplerSupplier.apply(rate);
  }

  public static void setGlobalLogRate(double rate) {
    GLOBAL_LOG_SAMPLER = samplerSupplier.apply(rate);
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

  public static void setSamplerSupplier(DoubleFunction<Sampler> samplerSupplier) {
    ProbeRateLimiter.samplerSupplier =
        samplerSupplier != null ? samplerSupplier : ProbeRateLimiter::createSampler;
  }

  private static Sampler createSampler(double rate) {
    if (rate < 0) {
      return new ConstantSampler(true);
    }
    if (rate < 1) {
      int intRate = (int) Math.round(rate * 10);
      return new AdaptiveSampler(TEN_SECONDS_WINDOW, intRate, 180, 16, true);
    }
    return new AdaptiveSampler(ONE_SECOND_WINDOW, (int) Math.round(rate), 180, 16, true);
  }

  private static class RateLimitInfo {
    final Sampler sampler;
    final boolean isCaptureSnapshot;

    public RateLimitInfo(Sampler sampler, boolean isCaptureSnapshot) {
      this.sampler = sampler;
      this.isCaptureSnapshot = isCaptureSnapshot;
    }
  }
}
