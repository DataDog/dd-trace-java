package datadog.trace.common.sampling;

import dagger.Module;
import dagger.Provides;
import datadog.trace.api.Config;
import java.util.Map;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Module
public class SamplerModule {
  private final Sampler sampler;

  public SamplerModule() {
    this(null);
  }

  public SamplerModule(final Sampler sampler) {
    this.sampler = sampler;
  }

  @Singleton
  @Provides
  Sampler sampler(final Config config) {
    if (sampler != null) {
      return sampler;
    }
    return fromConfig(config);
  }

  public static Sampler fromConfig(final Config config) {
    Sampler sampler;
    final Map<String, String> serviceRules = config.getTraceSamplingServiceRules();
    final Map<String, String> operationRules = config.getTraceSamplingOperationRules();

    if ((serviceRules != null && !serviceRules.isEmpty())
        || (operationRules != null && !operationRules.isEmpty())
        || config.getTraceSampleRate() != null) {

      try {
        sampler =
            RuleBasedSampler.build(
                serviceRules,
                operationRules,
                config.getTraceSampleRate(),
                config.getTraceRateLimit());
      } catch (final IllegalArgumentException e) {
        log.error("Invalid sampler configuration. Using AllSampler", e);
        sampler = new AllSampler();
      }
    } else if (config.isPrioritySamplingEnabled()) {
      sampler = new RateByServiceSampler();
    } else {
      sampler = new AllSampler();
    }
    return sampler;
  }
}
