package datadog.trace.core.propagation;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.util.Base64Encoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

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

  // assume that there is only one service and only latest sampling decision that matters
  private volatile ServiceSamplingDecision samplingDecision;

  private static final AtomicReferenceFieldUpdater<DatadogTags, ServiceSamplingDecision>
      SAMPLING_DECISION_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              DatadogTags.class, ServiceSamplingDecision.class, "samplingDecision");

  private static final class ServiceSamplingDecision {
    public final String service;
    public final int priority;
    public final int mechanism;
    public final double rate;

    private ServiceSamplingDecision(String service, int priority, int mechanism, double rate) {
      this.service = service;
      this.priority = priority;
      this.mechanism = mechanism;
      this.rate = rate;
    }

    public void encoded(StringBuilder sb) {
      String serviceNameBase64 =
          new String(
              BASE_64_ENCODER.encode(service.getBytes(StandardCharsets.UTF_8)),
              StandardCharsets.UTF_8);
      sb.append(serviceNameBase64);
      sb.append('|');
      sb.append(priority);
      sb.append('|');
      sb.append(mechanism);
      if (rate >= 0.0) {
        sb.append('|');
        sb.append(RATE_FORMATTER.format(rate));
      }
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
          new ServiceSamplingDecision(serviceName, priority, mechanism, rate);
      SAMPLING_DECISION_UPDATER.compareAndSet(this, samplingDecision, newSamplingDecision);
    }
  }

  /** @return encoded header value */
  public String encoded() {
    boolean isSamplingDecisionEmpty =
        samplingDecision == null || samplingDecision.priority == PrioritySampling.UNSET;
    if (rawTags.isEmpty()) {
      if (isSamplingDecisionEmpty) {
        return "";
      } else {
        StringBuilder sb = new StringBuilder();
        encodeUpstreamServices(sb);
        return sb.toString();
      }
    } else if (isSamplingDecisionEmpty) {
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
      samplingDecision.encoded(sb);
      return sb.toString();
    }

    // there is a tag following upstream_services, prepend prefix
    sb.append(rawTags.subSequence(0, upstreamEnd));
    // append sampling decisions
    sb.append(';');
    samplingDecision.encoded(sb);
    // append following tags
    sb.append(rawTags.subSequence(upstreamEnd, rawTags.length()));

    return sb.toString();
  }

  private void encodeUpstreamServices(StringBuilder sb) {
    sb.append(UPSTREAM_SERVICES);
    sb.append('=');
    samplingDecision.encoded(sb);
  }

  public void updateServiceName(String serviceName, String newServiceName) {
    // TODO update service name
    // do we even need to update it?
  }

  public boolean isEmpty() {
    return rawTags.isEmpty();
  }
}
