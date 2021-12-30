package datadog.trace.core;

import datadog.trace.util.Base64Encoder;

/** Encapsulates x-datadog-tags logic */
public class DatadogTags {

  private static final Base64Encoder base64Encoder = new Base64Encoder(false);

  private static final String UPSTREAM_SERVICES = "_dd.p.upstream_services";

  public static DatadogTags empty() {
    return new DatadogTags(null);
  }

  public static DatadogTags create(String value) {
    return new DatadogTags(value);
  }

  private final String rawTags;

  public DatadogTags(String rawTags) {
    this.rawTags = rawTags == null ? "" : rawTags;
  }

  /**
   * updates upstream services if needed
   *
   * @param serviceName - service name
   * @param priority - sampling priority
   * @param mechanism - sampling mechanism
   * @param rate - sampling rate, pass a negative value if not applicable
   */
  public void updateUpstreamServices(String serviceName, int priority, int mechanism, double rate) {
    // TODO collect changes in some separate structure and apply lazily
    // do not update if it hasn't change previous decision
  }

  /** @return encoded header value */
  public String encoded() {
    // TODO update if needed
    return rawTags;
  }

  public void updateServiceName(String serviceName, String newServiceName) {
    // TODO update service name
    // do we even need to update it?
  }

  public boolean isEmpty() {
    return rawTags.isEmpty();
  }
}
