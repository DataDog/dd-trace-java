package datadog.trace.bootstrap.debugger;

import datadog.trace.api.sampling.AdaptiveSampler;
import datadog.trace.api.sampling.ConstantSampler;
import datadog.trace.api.sampling.Sampler;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
  private static Sampler GLOBAL_SNAPSHOT_SAMPLER =
      defaultCreateSampler(DEFAULT_GLOBAL_SNAPSHOT_RATE);
  private static Sampler GLOBAL_LOG_SAMPLER = defaultCreateSampler(DEFAULT_GLOBAL_LOG_RATE);
  private static DoubleFunction<Sampler> samplerSupplier = ProbeRateLimiter::defaultCreateSampler;

  public static boolean tryProbe(Sampler sampler, boolean useGlobalLowRate) {
    Sampler globalSampler = useGlobalLowRate ? GLOBAL_SNAPSHOT_SAMPLER : GLOBAL_LOG_SAMPLER;
    if (globalSampler.sample()) {
      return sampler.sample();
    }
    return false;
  }

  private static RateLimitInfo getDefaultRateLimitInfo(String probeId) {
    LOGGER.debug("Setting sampling with default snapshot rate for probeId={}", probeId);
    return new RateLimitInfo(samplerSupplier.apply(DEFAULT_SNAPSHOT_RATE), true);
  }

  public static Sampler createSampler(double rate) {
    return samplerSupplier.apply(rate);
  }

  public static void setGlobalSnapshotRate(double rate) {
    GLOBAL_SNAPSHOT_SAMPLER = samplerSupplier.apply(rate);
  }

  public static void setGlobalLogRate(double rate) {
    GLOBAL_LOG_SAMPLER = samplerSupplier.apply(rate);
  }

  public static void resetGlobalRate() {
    setGlobalSnapshotRate(DEFAULT_GLOBAL_LOG_RATE);
  }

  public static void setSamplerSupplier(DoubleFunction<Sampler> samplerSupplier) {
    ProbeRateLimiter.samplerSupplier =
        samplerSupplier != null ? samplerSupplier : ProbeRateLimiter::defaultCreateSampler;
  }

  private static Sampler defaultCreateSampler(double rate) {
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
