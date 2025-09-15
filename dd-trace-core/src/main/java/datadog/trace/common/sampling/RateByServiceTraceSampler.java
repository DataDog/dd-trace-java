package datadog.trace.common.sampling;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.core.CoreSpan;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
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
  public static final String KNUTH_SAMPLING_RATE = "_dd.p.ksr";

  private static final double DEFAULT_RATE = 1.0;
  private static final DecimalFormat DECIMAL_FORMAT;
  
  static {
    DECIMAL_FORMAT = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    DECIMAL_FORMAT.setMaximumFractionDigits(6);
  }

  private volatile RateSamplersByEnvAndService serviceRates = new RateSamplersByEnvAndService();

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

    // Set Knuth sampling rate tag
    String ksrRate = formatKnuthSamplingRate(sampler.getSampleRate());
    span.setTag(KNUTH_SAMPLING_RATE, ksrRate);
  }

  private <T extends CoreSpan<T>> String getSpanEnv(final T span) {
    return span.getTag("env", "");
  }

  private String formatKnuthSamplingRate(double rate) {
    // Format to up to 6 decimal places, removing trailing zeros
    return DECIMAL_FORMAT.format(rate);
  }

  @Override
  public void onResponse(
      final String endpoint, final Map<String, Map<String, Number>> responseJson) {
    final Map<String, Number> newServiceRates = responseJson.get("rate_by_service");

    if (null == newServiceRates) {
      return;
    }

    log.debug("Update service sampler rates: {} -> {}", endpoint, responseJson);
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
        fallbackSampler = RateByServiceTraceSampler.createRateSampler(rate);
      } else {
        Map<String, RateSampler> serviceRates =
            updatedEnvServiceRates.computeIfAbsent(
                envAndService.lowerEnv, env -> new TreeMap<>(String::compareToIgnoreCase));

        serviceRates.computeIfAbsent(
            envAndService.lowerService,
            service -> RateByServiceTraceSampler.createRateSampler(rate));
      }
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
