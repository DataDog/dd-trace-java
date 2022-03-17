package datadog.trace.common.sampling;

import datadog.trace.api.Function;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.common.writer.ddagent.DDAgentResponseListener;
import datadog.trace.core.CoreSpan;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A rate sampler which maintains different sample rates per service+env name.
 *
 * <p>The configuration of (serviceName,env)->rate is configured by the core agent.
 */
public class RateByServiceSampler<T extends CoreSpan<T>>
    implements Sampler<T>, PrioritySampler<T>, DDAgentResponseListener {

  private static final Logger log = LoggerFactory.getLogger(RateByServiceSampler.class);
  public static final String SAMPLING_AGENT_RATE = "_dd.agent_psr";

  private static final double DEFAULT_RATE = 1.0;

  private volatile RateSamplersByEnvAndService<T> serviceRates =
      new RateSamplersByEnvAndService<>();

  @Override
  public boolean sample(final T span) {
    // Priority sampling sends all traces to the core agent, including traces marked dropped.
    // This allows the core agent to collect stats on all traces.
    return true;
  }

  /** If span is a root span, set the span context samplingPriority to keep or drop */
  @Override
  public void setSamplingPriority(final T span) {
    final String serviceName = span.getServiceName();
    final String env = getSpanEnv(span);

    final RateSamplersByEnvAndService<T> rates = serviceRates;
    RateSampler<T> sampler = rates.getSampler(new EnvAndService(env, serviceName));

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

  private String getSpanEnv(final T span) {
    return span.getTag("env", "");
  }

  @Override
  public void onResponse(
      final String endpoint, final Map<String, Map<String, Number>> responseJson) {
    final Map<String, Number> newServiceRates = responseJson.get("rate_by_service");
    if (null != newServiceRates) {
      log.debug("Update service sampler rates: {} -> {}", endpoint, responseJson);
      final Map<EnvAndService, RateSampler<T>> updatedServiceRates =
          new HashMap<>(newServiceRates.size() * 2);
      for (final Map.Entry<String, Number> entry : newServiceRates.entrySet()) {
        if (entry.getValue() != null) {
          updatedServiceRates.put(
              EnvAndService.fromString(entry.getKey()),
              RateByServiceSampler.<T>createRateSampler(entry.getValue().doubleValue()));
        }
      }
      serviceRates = new RateSamplersByEnvAndService<>(updatedServiceRates);
    }
  }

  private static <T extends CoreSpan<T>> RateSampler<T> createRateSampler(final double sampleRate) {
    final double sanitizedRate;
    if (sampleRate < 0) {
      log.error("SampleRate is negative or null, disabling the sampler");
      sanitizedRate = 1;
    } else if (sampleRate > 1) {
      sanitizedRate = 1;
    } else {
      sanitizedRate = sampleRate;
    }

    return new DeterministicSampler<>(sanitizedRate);
  }

  private static final class RateSamplersByEnvAndService<T extends CoreSpan<T>> {
    private static final RateSampler<?> DEFAULT = createRateSampler(DEFAULT_RATE);

    private final Map<EnvAndService, RateSampler<T>> serviceRates;

    RateSamplersByEnvAndService() {
      this(new HashMap<EnvAndService, RateSampler<T>>(0));
    }

    RateSamplersByEnvAndService(Map<EnvAndService, RateSampler<T>> serviceRates) {
      this.serviceRates = serviceRates;
    }

    @SuppressWarnings("unchecked")
    public RateSampler<T> getSampler(EnvAndService key) {
      RateSampler<T> sampler = serviceRates.get(key);
      return null == sampler ? (RateSampler<T>) DEFAULT : sampler;
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

    private final CharSequence env;
    private final CharSequence service;

    private EnvAndService(CharSequence env, CharSequence service) {
      this.env = env;
      this.service = service;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      EnvAndService that = (EnvAndService) o;
      return env.equals(that.env) && service.equals(that.service);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + env.hashCode();
      hash = 31 * hash + service.hashCode();
      return hash;
    }
  }
}
