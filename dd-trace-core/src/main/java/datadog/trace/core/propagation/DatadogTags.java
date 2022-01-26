package datadog.trace.core.propagation;

import java.util.Map;

/** Encapsulates x-datadog-tags logic */
public abstract class DatadogTags {

  public static DatadogTags empty() {
    return new DatadogTagsTracking(null);
  }

  public static DatadogTags create(String rawTags) {
    return new DatadogTagsTracking(rawTags);
  }

  public static DatadogTags noop() {
    return DatadogTagsNoop.INSTANCE;
  }

  /**
   * updates upstream services if needed
   *
   * @param serviceName - service name
   * @param priority - sampling priority
   * @param mechanism - sampling mechanism
   * @param rate - sampling rate, pass a negative value if not applicable
   */
  public abstract void updateUpstreamServices(
      String serviceName, int priority, int mechanism, double rate);

  /** @return true when both rawTags and upstream services are empty */
  public abstract boolean isEmpty();

  /**
   * Merges rawTags and the upstream_service sampling decision
   *
   * @return encoded header value or an empty string
   */
  public abstract String encodeAsHeaderValue();

  /**
   * Parses rawTags to a map and merges the upstream_service sampling decision to the result
   *
   * @return a new modifiable map containing tags or null
   */
  public abstract Map<String, String> parseAndMerge();
}
