package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.CLIENT_IP_KEY;
import static datadog.trace.core.propagation.HttpCodec.FORWARDED_FOR_KEY;
import static datadog.trace.core.propagation.HttpCodec.FORWARDED_KEY;
import static datadog.trace.core.propagation.HttpCodec.TRUE_CLIENT_IP_KEY;
import static datadog.trace.core.propagation.HttpCodec.USER_AGENT_KEY;
import static datadog.trace.core.propagation.HttpCodec.VIA_KEY;
import static datadog.trace.core.propagation.HttpCodec.X_CLUSTER_CLIENT_IP_KEY;
import static datadog.trace.core.propagation.HttpCodec.X_FORWARDED_FOR_KEY;
import static datadog.trace.core.propagation.HttpCodec.X_FORWARDED_HOST_KEY;
import static datadog.trace.core.propagation.HttpCodec.X_FORWARDED_KEY;
import static datadog.trace.core.propagation.HttpCodec.X_FORWARDED_PORT_KEY;
import static datadog.trace.core.propagation.HttpCodec.X_FORWARDED_PROTO_KEY;
import static datadog.trace.core.propagation.HttpCodec.X_REAL_IP_KEY;

import datadog.trace.api.Config;
import datadog.trace.api.DDId;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ForwardedTagContext;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public abstract class ContextInterpreter implements AgentPropagation.KeyClassifier {

  protected final Map<String, String> taggedHeaders;

  protected DDId traceId;
  protected DDId spanId;
  protected int samplingPriority;
  protected int samplingMechanism;
  protected Map<String, String> tags;
  protected Map<String, String> baggage;
  protected String origin;
  protected long endToEndStartTime;
  protected boolean hasForwarded;
  protected String forwarded;
  protected String forwardedProto;
  protected String forwardedHost;
  protected String forwardedIp;
  protected String forwardedPort;
  protected boolean valid;

  protected TagContext.HttpHeaders httpHeaders;
  protected boolean hasHttpHeaders;
  protected String customIpHeaderName;
  private boolean customIpHeaderDisabled;

  protected static final boolean LOG_EXTRACT_HEADER_NAMES = Config.get().isLogExtractHeaderNames();
  private static final DDCache<String, String> CACHE = DDCaches.newFixedSizeCache(64);

  protected String toLowerCase(String key) {
    return CACHE.computeIfAbsent(key, Functions.LowerCase.INSTANCE);
  }

  protected ContextInterpreter(Map<String, String> taggedHeaders) {
    this.taggedHeaders = taggedHeaders;
    Config config = Config.get();
    this.customIpHeaderName = config.getTraceClientIpHeader();
    this.customIpHeaderDisabled = config.isTraceClientIpHeaderDisabled();
    reset();
  }

  public abstract static class Factory {

    public ContextInterpreter create(Map<String, String> tagsMapping) {
      return construct(cleanMapping(tagsMapping));
    }

    protected abstract ContextInterpreter construct(Map<String, String> tagsMapping);

    protected Map<String, String> cleanMapping(Map<String, String> taggedHeaders) {
      final Map<String, String> cleanedMapping = new HashMap<>(taggedHeaders.size() * 4 / 3);
      for (Map.Entry<String, String> association : taggedHeaders.entrySet()) {
        cleanedMapping.put(
            association.getKey().trim().toLowerCase(), association.getValue().trim().toLowerCase());
      }
      return cleanedMapping;
    }
  }

  protected final boolean handledForwarding(String key, String value) {
    if (null != value) {
      if (FORWARDED_KEY.equalsIgnoreCase(key)) {
        forwarded = value;
        hasForwarded = true;
        return true;
      }
      if (FORWARDED_FOR_KEY.equalsIgnoreCase(key)) {
        httpHeaders.forwardedFor = value;
        hasHttpHeaders = true;
        return true;
      }
    }
    return false;
  }

  protected final boolean handledXForwarding(String key, String value) {
    if (null != value) {
      if (X_FORWARDED_PROTO_KEY.equalsIgnoreCase(key)) {
        forwardedProto = value;
        hasForwarded = true;
        return true;
      }
      if (X_FORWARDED_HOST_KEY.equalsIgnoreCase(key)) {
        forwardedHost = value;
        hasForwarded = true;
        return true;
      }
      if (X_FORWARDED_FOR_KEY.equalsIgnoreCase(key)) {
        forwardedIp = value;
        hasForwarded = true;
        httpHeaders.xForwardedFor = value;
        hasHttpHeaders = true;
        return true;
      }
      if (X_FORWARDED_PORT_KEY.equalsIgnoreCase(key)) {
        forwardedPort = value;
        hasForwarded = true;
        return true;
      }
      if (X_FORWARDED_KEY.equalsIgnoreCase(key)) {
        httpHeaders.xForwarded = value;
        hasHttpHeaders = true;
        return true;
      }
    }
    return false;
  }

  protected final boolean handledUserAgent(String key, String value) {
    if (null != value && USER_AGENT_KEY.equalsIgnoreCase(key)) {
      httpHeaders.userAgent = value;
      hasHttpHeaders = true;
      return true;
    }
    return false;
  }

  protected final boolean handledIpHeaders(String key, String value) {
    if (key == null || key.isEmpty()) {
      return false;
    }

    if (null != value
        && customIpHeaderName != null
        && !customIpHeaderDisabled
        && customIpHeaderName.equalsIgnoreCase(key)) {
      httpHeaders.customIpHeader = value;
      hasHttpHeaders = true;
      return true;
    }

    if (null != value) {
      // May be ends with 'ip' ?
      char last = Character.toLowerCase(key.charAt(key.length() - 1));
      if (last == 'p') {
        if (X_CLUSTER_CLIENT_IP_KEY.equalsIgnoreCase(key)) {
          httpHeaders.xClusterClientIp = value;
          hasHttpHeaders = true;
          return true;
        }
        if (X_REAL_IP_KEY.equalsIgnoreCase(key)) {
          httpHeaders.xRealIp = value;
          hasHttpHeaders = true;
          return true;
        }
        if (CLIENT_IP_KEY.equalsIgnoreCase(key)) {
          httpHeaders.clientIp = value;
          hasHttpHeaders = true;
          return true;
        }
        if (TRUE_CLIENT_IP_KEY.equalsIgnoreCase(key)) {
          httpHeaders.trueClientIp = value;
          hasHttpHeaders = true;
          return true;
        }
      }

      if (VIA_KEY.equalsIgnoreCase(key)) {
        httpHeaders.via = value;
        hasHttpHeaders = true;
        return true;
      }
    }
    return false;
  }

  public ContextInterpreter reset() {
    traceId = DDId.ZERO;
    spanId = DDId.ZERO;
    samplingPriority = defaultSamplingPriority();
    samplingMechanism = defaultSamplingMechanism();
    origin = null;
    endToEndStartTime = 0;
    hasForwarded = false;
    forwarded = null;
    forwardedProto = null;
    forwardedHost = null;
    forwardedIp = null;
    forwardedPort = null;
    tags = Collections.emptyMap();
    baggage = Collections.emptyMap();
    valid = true;
    httpHeaders = new TagContext.HttpHeaders();
    hasHttpHeaders = false;
    return this;
  }

  TagContext build() {
    if (valid) {
      if (!DDId.ZERO.equals(traceId)) {
        final ExtractedContext context;
        if (hasForwarded) {
          context =
              new ForwardedExtractedContext(
                  traceId,
                  spanId,
                  samplingPriority,
                  samplingMechanism,
                  origin,
                  endToEndStartTime,
                  forwarded,
                  forwardedProto,
                  forwardedHost,
                  forwardedIp,
                  forwardedPort,
                  baggage,
                  tags,
                  httpHeaders);
        } else {
          context =
              new ExtractedContext(
                  traceId,
                  spanId,
                  samplingPriority,
                  samplingMechanism,
                  origin,
                  endToEndStartTime,
                  baggage,
                  tags,
                  httpHeaders);
        }
        return context;
      } else if (hasForwarded) {
        return new ForwardedTagContext(
            origin,
            forwarded,
            forwardedProto,
            forwardedHost,
            forwardedIp,
            forwardedPort,
            tags,
            httpHeaders);
      } else if (origin != null || !tags.isEmpty() || hasHttpHeaders) {
        return new TagContext(origin, tags, httpHeaders);
      }
    }
    return null;
  }

  protected void invalidateContext() {
    this.valid = false;
  }

  protected int defaultSamplingPriority() {
    return PrioritySampling.UNSET;
  }

  protected int defaultSamplingMechanism() {
    return SamplingMechanism.UNKNOWN;
  }
}
