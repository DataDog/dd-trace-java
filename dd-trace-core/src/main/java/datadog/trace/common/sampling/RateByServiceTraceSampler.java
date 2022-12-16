package datadog.trace.common.sampling;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.core.CoreSpan;
import java.util.HashMap;
import java.util.Map;
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

  private static final double DEFAULT_RATE = 1.0;

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
  }

  private <T extends CoreSpan<T>> String getSpanEnv(final T span) {
    return span.getTag("env", "");
  }

  @Override
  public void onResponse(
      final String endpoint, final Map<String, Map<String, Number>> responseJson) {
    final Map<String, Number> newServiceRates = responseJson.get("rate_by_service");
    if (null != newServiceRates) {
      log.debug("Update service sampler rates: {} -> {}", endpoint, responseJson);
      final Map<String, Map<String, RateSampler>> updatedEnvServiceRates =
          new HashMap<>(newServiceRates.size() * 2);
      for (final Map.Entry<String, Number> entry : newServiceRates.entrySet()) {
        if (entry.getValue() != null) {
          EnvAndService envAndService = EnvAndService.fromString(entry.getKey());
          Map<String, RateSampler> serviceRates =
              updatedEnvServiceRates.computeIfAbsent(
                  envAndService.env, env -> new HashMap<>(newServiceRates.size() * 2));
          serviceRates.computeIfAbsent(
              envAndService.service,
              service ->
                  RateByServiceTraceSampler.createRateSampler(entry.getValue().doubleValue()));
        }
      }
      serviceRates = new RateSamplersByEnvAndService(updatedEnvServiceRates);
    }
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
    private static final RateSampler DEFAULT = createRateSampler(DEFAULT_RATE);

    private final Map<String, Map<String, RateSampler>> envServiceRates;

    RateSamplersByEnvAndService() {
      this(new HashMap<>(0));
    }

    RateSamplersByEnvAndService(Map<String, Map<String, RateSampler>> envServiceRates) {
      this.envServiceRates = envServiceRates;
    }

    // used in tests only
    RateSampler getSampler(EnvAndService envAndService) {
      return getSampler(envAndService.env, envAndService.service);
    }

    public RateSampler getSampler(String env, String service) {
      Map<String, RateSampler> serviceRates = envServiceRates.get(env);
      if (serviceRates == null) {
        return DEFAULT;
      }
      RateSampler sampler = serviceRates.get(service);
      return null == sampler ? DEFAULT : sampler;
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
            // both empty or at least one invalid
            if ((serviceStart == serviceEnd && envStart == key.length())
                || (serviceStart | serviceEnd | envStart) < 0) {
              return DEFAULT;
            }
            String service = key.substring(serviceStart, serviceEnd);
            String env = key.substring(envStart);
            return new EnvAndService(env, service);
          }
        };

    static final EnvAndService DEFAULT = new EnvAndService("", "");

    public static EnvAndService fromString(String key) {
      return CACHE.computeIfAbsent(key, PARSE);
    }

    private final String env;
    private final String service;

    private EnvAndService(String env, String service) {
      this.env = env;
      this.service = service;
    }
  }
}
