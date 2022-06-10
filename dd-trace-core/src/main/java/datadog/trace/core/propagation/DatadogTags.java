package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.TRACE_X_DATADOG_TAGS_MAX_LENGTH_DEFAULT_VALUE;

import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates logic related to the Service Propagation including:
 *
 * <pre>
 *   - parsing and validation the x-datadog-tags header value
 *   - dropping non _dd.p.* tags
 *   - error handling and propagation
 *   - concurrent updates to the sampling priority
 *   - serialization to the x-datadog-tags header format
 *   - getting actual Datadog tags map
 * </pre>
 */
public abstract class DatadogTags {

  public static DatadogTagsFactory factory(
      boolean isServicePropagationEnabled, int datadogTagsLimit) {
    return new DatadogTagsFactory(isServicePropagationEnabled, datadogTagsLimit);
  }

  public static DatadogTagsFactory factory(Config config) {
    return factory(config.isServicePropagationEnabled(), config.getDataDogTagsLimit());
  }

  public static DatadogTagsFactory factory() {
    return factory(true, TRACE_X_DATADOG_TAGS_MAX_LENGTH_DEFAULT_VALUE);
  }

  /** Called on the span context that made a sampling decision to keep the trace */
  public abstract void updateSpanSamplingPriority(int samplingPriority, String serviceName);

  /** Called on the root span context */
  public abstract void updateTraceSamplingPriority(
      int samplingPriority, int samplingMechanism, String serviceName);

  public abstract String headerValue();

  public abstract void fillTagMap(Map<String, String> tagMap);

  public HashMap<String, String> createTagMap() {
    HashMap<String, String> result = new HashMap<>();
    fillTagMap(result);
    return result;
  }
}
