package datadog.trace.common.sampling;

import datadog.trace.api.Function;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ddagent.DDAgentResponseListener;
import datadog.trace.core.DDSpan;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * A rate sampler which maintains different sample rates per service+env name.
 *
 * <p>The configuration of (serviceName,env)->rate is configured by the core agent.
 */
@Slf4j
public class RateByServiceSampler implements Sampler, PrioritySampler, DDAgentResponseListener {
  public static final String SAMPLING_AGENT_RATE = "_dd.agent_psr";

  private static final double DEFAULT_RATE = 1.0;

  private volatile RateSamplersByEnvAndService serviceRates = new RateSamplersByEnvAndService();

  @Override
  public boolean sample(final DDSpan span) {
    // Priority sampling sends all traces to the core agent, including traces marked dropped.
    // This allows the core agent to collect stats on all traces.
    return true;
  }

  /** If span is a root span, set the span context samplingPriority to keep or drop */
  @Override
  public void setSamplingPriority(final DDSpan span) {
    final String serviceName = span.getServiceName();
    final String env = getSpanEnv(span);

    final RateSamplersByEnvAndService rates = serviceRates;
    RateSampler sampler = rates.getSampler(new EnvAndService(env, serviceName));

    final boolean priorityWasSet;

    if (sampler.sample(span)) {
      priorityWasSet = span.context().setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    } else {
      priorityWasSet = span.context().setSamplingPriority(PrioritySampling.SAMPLER_DROP);
    }

    // Only set metrics if we actually set the sampling priority
    // We don't know until the call is completed because the lock is internal to DDSpanContext
    if (priorityWasSet) {
      span.context().setMetric(SAMPLING_AGENT_RATE, sampler.getSampleRate());
    }
  }

  private static String getSpanEnv(final DDSpan span) {
    Object env = span.getTag("env");
    return null == env ? "" : String.valueOf(env);
  }

  @Override
  public void onResponse(
      final String endpoint, final Map<String, Map<String, Number>> responseJson) {
    final Map<String, Number> newServiceRates = responseJson.get("rate_by_service");
    if (null != newServiceRates) {
      log.debug("Update service sampler rates: {} -> {}", endpoint, responseJson);
      final Map<EnvAndService, RateSampler> updatedServiceRates =
          new HashMap<>(newServiceRates.size() * 2);
      for (final Map.Entry<String, Number> entry : newServiceRates.entrySet()) {
        if (entry.getValue() != null) {
          updatedServiceRates.put(
              EnvAndService.fromString(entry.getKey()),
              createRateSampler(entry.getValue().doubleValue()));
        }
      }
      serviceRates = new RateSamplersByEnvAndService(updatedServiceRates);
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

    return new DeterministicSampler(sanitizedRate);
  }

  private static final class RateSamplersByEnvAndService {
    private static final RateSampler DEFAULT = createRateSampler(DEFAULT_RATE);

    private final Map<EnvAndService, RateSampler> serviceRates;

    RateSamplersByEnvAndService() {
      this(new HashMap<EnvAndService, RateSampler>(0));
    }

    RateSamplersByEnvAndService(Map<EnvAndService, RateSampler> serviceRates) {
      this.serviceRates = serviceRates;
    }

    public RateSampler getSampler(EnvAndService key) {
      RateSampler sampler = serviceRates.get(key);
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
      return Objects.hash(env, service);
    }
  }
}
