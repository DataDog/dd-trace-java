package datadog.trace.core.propagation;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.util.Base64Encoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Encapsulates x-datadog-tags logic */
public class DatadogTags {

  private static final Base64Encoder BASE_64_ENCODER = new Base64Encoder(false);

  private static final String UPSTREAM_SERVICES = "_dd.p.upstream_services";

  private static final DecimalFormat RATE_FORMATTER = new DecimalFormat("#.####");

  public static DatadogTags empty() {
    return new DatadogTags(null);
  }

  public static DatadogTags create(String value) {
    return new DatadogTags(value);
  }

  private final String rawTags;
  private final ConcurrentHashMap<String, ServiceSamplingDecision> serviceSamplingDecisions =
      new ConcurrentHashMap<>();

  private static final class ServiceSamplingDecision {
    public final int priority;
    public final int mechanism;
    public final double rate;

    private ServiceSamplingDecision(int priority, int mechanism, double rate) {
      this.priority = priority;
      this.mechanism = mechanism;
      this.rate = rate;
    }

    public String encoded() {
      return priority + "|" + mechanism + (rate >= 0.0 ? "|" + RATE_FORMATTER.format(rate) : "");
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ServiceSamplingDecision that = (ServiceSamplingDecision) o;
      return priority == that.priority
          && mechanism == that.mechanism
          && Double.compare(that.rate, rate) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(priority, mechanism, rate);
    }
  }

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
    if (priority != PrioritySampling.UNSET) {
      ServiceSamplingDecision newSamplingDecision =
          new ServiceSamplingDecision(priority, mechanism, rate);
      serviceSamplingDecisions.put(serviceName, newSamplingDecision);
    }
  }

  /** @return encoded header value */
  public String encoded() {
    if (rawTags.isEmpty()) {
      if (serviceSamplingDecisions.isEmpty()) {
        return "";
      } else {
        StringBuilder sb = new StringBuilder();
        encodeUpstreamServices(sb);
        return sb.toString();
      }
    } else if (serviceSamplingDecisions.isEmpty()) {
      // nothing has changed, return rawTags as is
      return rawTags;
    }

    StringBuilder sb = new StringBuilder();

    // find upstream services and the following coma or the end of the string
    int upstreamStart = rawTags.indexOf(UPSTREAM_SERVICES);
    if (upstreamStart < 0) {
      // there is no upstream_services tag, append new to the end
      sb.append(rawTags);
      sb.append(',');
      encodeUpstreamServices(sb);
      return sb.toString();
    }

    // there is upstream_services tag
    int upstreamEnd = rawTags.indexOf(',', upstreamStart);
    if (upstreamEnd < 0) {
      // upstream_services tag is the last, prepend whole rawTags
      sb.append(rawTags);
      sb.append(';');
      encodeUpstreamServicesHeadless(sb);
      return sb.toString();
    }

    // there is a tag following upstream_services, prepend prefix
    sb.append(rawTags.substring(0, upstreamEnd));
    // append sampling decisions
    sb.append(';');
    encodeUpstreamServicesHeadless(sb);
    // append following tags
    sb.append(rawTags.substring(upstreamEnd, rawTags.length()));

    return sb.toString();
  }

  private void encodeUpstreamServices(StringBuilder sb) {
    sb.append(UPSTREAM_SERVICES);
    sb.append('=');
    encodeUpstreamServicesHeadless(sb);
  }

  private void encodeUpstreamServicesHeadless(StringBuilder sb) {
    for (Map.Entry<String, ServiceSamplingDecision> serviceSamplingDecision :
        serviceSamplingDecisions.entrySet()) {
      String serviceNameBase64 =
          new String(
              BASE_64_ENCODER.encode(
                  serviceSamplingDecision.getKey().getBytes(StandardCharsets.UTF_8)),
              StandardCharsets.UTF_8);
      sb.append(serviceNameBase64);
      sb.append('|');
      sb.append(serviceSamplingDecision.getValue().encoded());
      sb.append(';');
    }
    sb.delete(sb.length() - 1, sb.length()); // remove trailing ';'
  }

  public void updateServiceName(String serviceName, String newServiceName) {
    // TODO update service name
    // do we even need to update it?
  }

  public boolean isEmpty() {
    return rawTags.isEmpty();
  }
}
