package datadog.trace.core.propagation;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.util.Base64Encoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DatadogTagsTracking extends DatadogTags {
  private static final Logger log = LoggerFactory.getLogger(DatadogTagsTracking.class);

  private static final Base64Encoder BASE_64_ENCODER = new Base64Encoder(false);
  private static final String UPSTREAM_SERVICES = "_dd.p.upstream_services";
  private static final DecimalFormat RATE_FORMATTER = new DecimalFormat("#.####");

  private final String rawTags;

  // assume that there is only one service and only latest sampling decision matters
  private volatile ServiceSamplingDecision samplingDecision;
  private static final AtomicReferenceFieldUpdater<DatadogTagsTracking, ServiceSamplingDecision>
      SAMPLING_DECISION_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              DatadogTagsTracking.class, ServiceSamplingDecision.class, "samplingDecision");

  private static final class ServiceSamplingDecision {
    private final String service;
    private final int priority;
    private final int mechanism;
    private final double rate;

    private ServiceSamplingDecision(String service, int priority, int mechanism, double rate) {
      this.service = service;
      this.priority = priority;
      this.mechanism = mechanism;
      this.rate = rate;
    }

    public void encode(StringBuilder sb) {
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

    public boolean isUnset() {
      return priority == PrioritySampling.UNSET;
    }
  }

  DatadogTagsTracking(String rawTags) {
    this.rawTags = rawTags == null ? "" : rawTags;
  }

  @Override
  public void updateUpstreamServices(String serviceName, int priority, int mechanism, double rate) {
    if (priority != PrioritySampling.UNSET
        && (samplingDecision == null
            || samplingDecision.priority != priority
            || samplingDecision.mechanism != mechanism
            || samplingDecision.rate != rate
            || !samplingDecision.service.equals(serviceName))) {
      ServiceSamplingDecision newSamplingDecision =
          new ServiceSamplingDecision(serviceName, priority, mechanism, rate);
      SAMPLING_DECISION_UPDATER.compareAndSet(this, samplingDecision, newSamplingDecision);
    }
  }

  @Override
  public boolean isEmpty() {
    return rawTags.isEmpty() && samplingDecision == null;
  }

  @Override
  public String encodeAsHeaderValue() {
    boolean isSamplingDecisionEmpty = samplingDecision == null || samplingDecision.isUnset();
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

    // there is only upstream_services tag
    int upstreamEnd = rawTags.indexOf(',', upstreamStart);
    if (upstreamEnd < 0) {
      // upstream_services tag is the last, prepend whole rawTags
      sb.append(rawTags);
      appendUpstreamServicesEncoded(sb, rawTags.length() - 1);
      return sb.toString();
    }

    // there is a tag following upstream_services, prepend it as is to avoid parsing
    sb.append(rawTags.subSequence(0, upstreamEnd));
    appendUpstreamServicesEncoded(sb, upstreamEnd - 1);
    // append following tags
    sb.append(rawTags.subSequence(upstreamEnd, rawTags.length()));

    return sb.toString();
  }

  @Override
  public Map<String, String> parseAndMerge() {
    Map<String, String> result = new HashMap<>();
    if (!rawTags.isEmpty()) {
      int startIndex = 0;
      // split rawTags and put them into the map
      while (startIndex < rawTags.length()) {
        String tagName;
        int tagNamePosition = rawTags.indexOf('=', startIndex);
        if (tagNamePosition > 0) {
          tagName = rawTags.substring(startIndex, tagNamePosition);
        } else {
          // tag name without following `=`
          log.warn("Malformed Datadog tags `{}` won't be sent to the backend!", rawTags);
          return null;
        }
        startIndex = tagNamePosition + 1;

        int tagValueEnds = rawTags.indexOf(',', startIndex);
        if (tagValueEnds < 0) {
          tagValueEnds = rawTags.length();
        }
        String tagValue = rawTags.substring(startIndex, tagValueEnds);
        if (!tagValue.isEmpty()) {
          result.put(tagName, tagValue);
        }
        startIndex = tagValueEnds + 1;
      }
    }
    // add upstream_services
    String upstreamServices = result.get(UPSTREAM_SERVICES);
    StringBuilder sb = new StringBuilder();
    boolean nonEmptySamplingDecision = samplingDecision != null;
    if (upstreamServices != null) {
      sb.append(upstreamServices);
      if (nonEmptySamplingDecision) {
        sb.append(';');
      }
    }
    if (nonEmptySamplingDecision) {
      samplingDecision.encode(sb);
    }
    if (sb.length() > 0) {
      result.put(UPSTREAM_SERVICES, sb.toString());
    }
    return result;
  }

  private void appendUpstreamServicesEncoded(StringBuilder sb, int lastCharIndex) {
    char lastChar = rawTags.charAt(lastCharIndex);
    // check if a separator needed
    if (lastChar != '=' && lastChar != ';') {
      sb.append(';');
    }
    samplingDecision.encode(sb);
  }

  private void encodeUpstreamServices(StringBuilder sb) {
    sb.append(UPSTREAM_SERVICES);
    sb.append('=');
    samplingDecision.encode(sb);
  }
}
