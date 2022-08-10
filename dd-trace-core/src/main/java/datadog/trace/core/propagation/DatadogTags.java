package datadog.trace.core.propagation;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;

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
 *   - producing the x-datadog-tags header value
 *   - producing meta tags to be sent to the agent
 * </pre>
 */
public abstract class DatadogTags {

  public static DatadogTags.Factory factory(Config config) {
    return factory(config.getxDatadogTagsMaxLength());
  }

  public static DatadogTags.Factory factory(int datadogTagsLimit) {
    return new DatadogTagsFactory(datadogTagsLimit);
  }

  public static DatadogTags.Factory factory() {
    return factory(DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH);
  }

  public interface Factory {
    DatadogTags empty();

    DatadogTags fromHeaderValue(String value);
  }

  /**
   * Updates the trace-level sampling priority decision if it hasn't already been made and _dd.p.dm
   * tag doesn't exist. Called on the root span context.
   */
  public abstract void updateTraceSamplingPriority(
      int samplingPriority, int samplingMechanism, String serviceName);

  /**
   * Constructs a header value that includes valid propagated _dd.p.* tags and possibly a new
   * sampling decision tag _dd.p.dm based on the current state. Returns null if the value length
   * exceeds a configured limit or empty.
   */
  public abstract String headerValue();

  /**
   * Fills a provided tagMap with valid propagated _dd.p.* tags and possibly a new sampling decision
   * tags _dd.p.dm (root span only) based on the current state, or sets only an error tag if the
   * header value exceeds a configured limit.
   */
  public abstract void fillTagMap(Map<String, String> tagMap);

  public HashMap<String, String> createTagMap() {
    HashMap<String, String> result = new HashMap<>();
    fillTagMap(result);
    return result;
  }
}
