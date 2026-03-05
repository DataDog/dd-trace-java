package datadog.trace.common.sampling;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.core.CoreSpan;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A rate sampler which maintains different sample rates per service+env name.
 *
 * <p>The configuration of (serviceName,env)->rate is configured by the core agent.
 */
public class RateByServiceTraceSampler implements Sampler, PrioritySampler, RemoteResponseListener {

  private static final Logger log = LoggerFactory.getLogger(RateByServiceTraceSampler.class);
  public static final String SAMPLING_AGENT_RATE = "_dd.agent_psr";

  private static final double DEFAULT_RATE = 1.0;
  private static final double MAX_RATE_INCREASE_FACTOR = 2.0;
  static final long RAMP_UP_INTERVAL_NANOS = 1_000_000_000L;

  private volatile RateSamplersByEnvAndService serviceRates = new RateSamplersByEnvAndService();
  private long lastCappedNanos;
  LongSupplier nanoTimeSupplier = System::nanoTime;

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    // Priority sampling sends all traces to the core agent, including traces marked dropped.
    // This allows the core agent to collect stats on all traces.
    return true;
  }

  /** If span is a root span, set the span context samplingPriority to keep or drop */
  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {
    final String serviceName = span.getServiceName();
    final String env = getSpanEnv(span);

    final RateSamplersByEnvAndService rates = serviceRates;
    RateSampler sampler = rates.getSampler(env, serviceName);

    if (sampler.sample(span)) {
      span.setSamplingPriority(
          PrioritySampling.SAMPLER_KEEP,
          SAMPLING_AGENT_RATE,
          sampler.getSampleRate(),
          SamplingMechanism.AGENT_RATE);
    } else {
      span.setSamplingPriority(
          PrioritySampling.SAMPLER_DROP,
          SAMPLING_AGENT_RATE,
          sampler.getSampleRate(),
          SamplingMechanism.AGENT_RATE);
    }
  }

  private <T extends CoreSpan<T>> String getSpanEnv(final T span) {
    return span.getTag("env", "");
  }

  static boolean shouldCap(double oldRate, double newRate) {
    return oldRate != 0 && newRate > oldRate * MAX_RATE_INCREASE_FACTOR;
  }

  static double cappedRate(double oldRate) {
    return oldRate * MAX_RATE_INCREASE_FACTOR;
  }

  @Override
  public void onResponse(
      final String endpoint, final Map<String, Map<String, Number>> responseJson) {
    final Map<String, Number> newServiceRates = responseJson.get("rate_by_service");

    if (null == newServiceRates) {
      return;
    }

    log.debug("Update service sampler rates: {} -> {}", endpoint, responseJson);

    final RateSamplersByEnvAndService currentSnapshot = serviceRates;
    final long now = nanoTimeSupplier.getAsLong();
    final boolean canIncrease =
        lastCappedNanos == 0 || (now - lastCappedNanos) >= RAMP_UP_INTERVAL_NANOS;
    boolean anyCapped = false;

    final TreeMap<String, TreeMap<String, RateSampler>> updatedEnvServiceRates =
        new TreeMap<>(String::compareToIgnoreCase);

    RateSampler fallbackSampler = RateSamplersByEnvAndService.DEFAULT_SAMPLER;
    for (final Map.Entry<String, Number> entry : newServiceRates.entrySet()) {
      if (entry.getValue() == null) {
        continue;
      }
      double rate = entry.getValue().doubleValue();

      EnvAndService envAndService = EnvAndService.fromString(entry.getKey());
      if (envAndService.isFallback()) {
        double oldRate = currentSnapshot.getFallbackSampler().getSampleRate();
        if (canIncrease && shouldCap(oldRate, rate)) {
          rate = cappedRate(oldRate);
          anyCapped = true;
        } else if (!canIncrease && shouldCap(oldRate, rate)) {
          rate = oldRate;
        }
        fallbackSampler = RateByServiceTraceSampler.createRateSampler(rate);
      } else {
        double oldRate =
            currentSnapshot
                .getSampler(envAndService.lowerEnv, envAndService.lowerService)
                .getSampleRate();
        if (canIncrease && shouldCap(oldRate, rate)) {
          rate = cappedRate(oldRate);
          anyCapped = true;
        } else if (!canIncrease && shouldCap(oldRate, rate)) {
          rate = oldRate;
        }
        final double effectiveRate = rate;
        Map<String, RateSampler> serviceRates =
            updatedEnvServiceRates.computeIfAbsent(
                envAndService.lowerEnv, env -> new TreeMap<>(String::compareToIgnoreCase));

        serviceRates.computeIfAbsent(
            envAndService.lowerService,
            service -> RateByServiceTraceSampler.createRateSampler(effectiveRate));
      }
    }
    if (canIncrease && anyCapped) {
      lastCappedNanos = now;
    }
    serviceRates = new RateSamplersByEnvAndService(updatedEnvServiceRates, fallbackSampler);
  }

  private static RateSampler createRateSampler(final double sampleRate) {
    final double sanitizedRate;
    if (sampleRate < 0) {
      log.error("SampleRate is negative or null, disabling the sampler");
      sanitizedRate = 1;
    } else if (sampleRate > 1) {
      sanitizedRate = 1;
    } else {
      sanitizedRate = sampleRate;
    }

    return new DeterministicSampler.TraceSampler(sanitizedRate);
  }

  private static final class RateSamplersByEnvAndService {
    private static final RateSampler DEFAULT_SAMPLER = createRateSampler(DEFAULT_RATE);

    private final Map<String, TreeMap<String, RateSampler>> envServiceRates;
    private final RateSampler fallbackSampler;

    RateSamplersByEnvAndService() {
      this(Collections.emptyMap(), DEFAULT_SAMPLER);
    }

    RateSamplersByEnvAndService(
        Map<String, TreeMap<String, RateSampler>> envServiceRates, RateSampler fallbackSampler) {
      this.envServiceRates = envServiceRates;
      this.fallbackSampler = fallbackSampler;
    }

    RateSampler getFallbackSampler() {
      return fallbackSampler;
    }

    // used in tests only
    RateSampler getSampler(EnvAndService envAndService) {
      return getSampler(envAndService.lowerEnv, envAndService.lowerService);
    }

    public RateSampler getSampler(String env, String service) {
      if (EnvAndService.isFallback(env, service)) {
        return fallbackSampler;
      }

      Map<String, RateSampler> serviceRates = envServiceRates.get(env);
      if (serviceRates == null) {
        return fallbackSampler;
      }
      RateSampler sampler = serviceRates.get(service);
      return null == sampler ? fallbackSampler : sampler;
    }
  }

  private static final class EnvAndService {
    private static final DDCache<String, EnvAndService> CACHE = DDCaches.newFixedSizeCache(32);

    private static final Function<String, EnvAndService> PARSE =
        new Function<String, EnvAndService>() {

          @Override
          public EnvAndService apply(String key) {
            // "service:,env:"
            int serviceStart = key.indexOf(':') + 1;
            int serviceEnd = key.indexOf(',', serviceStart);
            int envStart = key.indexOf(':', serviceEnd) + 1;
            int envEnd = key.length();

            // both empty or at least one invalid
            if ((serviceStart == serviceEnd && envStart == envEnd)
                || (serviceStart | serviceEnd | envStart) < 0) {
              return FALLBACK;
            }

            String service = key.substring(serviceStart, serviceEnd);
            String env = key.substring(envStart);

            // EnvAndService will toLower the values
            return new EnvAndService(env, service);
          }
        };

    static final EnvAndService FALLBACK = new EnvAndService("", "");

    public static EnvAndService fromString(String key) {
      return CACHE.computeIfAbsent(key, PARSE);
    }

    private final String lowerEnv;
    private final String lowerService;

    private EnvAndService(String env, String service) {
      lowerEnv = env.toLowerCase();
      lowerService = service.toLowerCase();
    }

    boolean isFallback() {
      return isFallback(lowerEnv, lowerService);
    }

    private static final boolean isFallback(String env, String service) {
      return env.isEmpty() && service.isEmpty();
    }
  }
}
