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
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public abstract class ContextInterpreter implements AgentPropagation.KeyClassifier {

  protected final Map<String, String> taggedHeaders;

  protected DDTraceId traceId;
  protected long spanId;
  protected int samplingPriority;
  protected Map<String, String> tags;
  protected Map<String, String> baggage;
  protected String origin;
  protected long endToEndStartTime;
  protected boolean valid;
  protected DatadogTags datadogTags;

  private TagContext.HttpHeaders httpHeaders;
  private final String customIpHeaderName;
  private final boolean clientIpResolutionEnabled;
  private final boolean clientIpWithoutAppSec;
  private boolean collectIpHeaders;

  protected static final boolean LOG_EXTRACT_HEADER_NAMES = Config.get().isLogExtractHeaderNames();
  private static final DDCache<String, String> CACHE = DDCaches.newFixedSizeCache(64);

  protected String toLowerCase(String key) {
    return CACHE.computeIfAbsent(key, Functions.LowerCase.INSTANCE);
  }

  protected ContextInterpreter(Map<String, String> taggedHeaders, Config config) {
    this.taggedHeaders = taggedHeaders;
    this.customIpHeaderName = config.getTraceClientIpHeader();
    this.clientIpResolutionEnabled = config.isTraceClientIpResolverEnabled();
    this.clientIpWithoutAppSec = config.isClientIpEnabled();
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
    if (value == null || !collectIpHeaders) {
      return false;
    }

    if (FORWARDED_KEY.equalsIgnoreCase(key)) {
      getHeaders().forwarded = value;
      return true;
    }
    if (FORWARDED_FOR_KEY.equalsIgnoreCase(key)) {
      getHeaders().forwardedFor = value;
      return true;
    }
    return false;
  }

  protected final boolean handledXForwarding(String key, String value) {
    if (value == null || !collectIpHeaders) {
      return false;
    }

    if (X_FORWARDED_PROTO_KEY.equalsIgnoreCase(key)) {
      getHeaders().xForwardedProto = value;
      return true;
    }
    if (X_FORWARDED_HOST_KEY.equalsIgnoreCase(key)) {
      getHeaders().xForwardedHost = value;
      return true;
    }
    if (X_FORWARDED_FOR_KEY.equalsIgnoreCase(key)) {
      getHeaders().xForwardedFor = value;
      return true;
    }
    if (X_FORWARDED_PORT_KEY.equalsIgnoreCase(key)) {
      getHeaders().xForwardedPort = value;
      return true;
    }
    if (X_FORWARDED_KEY.equalsIgnoreCase(key)) {
      getHeaders().xForwarded = value;
      return true;
    }
    return false;
  }

  protected final boolean handledUserAgent(String key, String value) {
    if (value == null || !USER_AGENT_KEY.equalsIgnoreCase(key)) {
      return false;
    }

    getHeaders().userAgent = value;
    return true;
  }

  protected final boolean handledIpHeaders(String key, String value) {
    if (key == null || key.isEmpty()) {
      return false;
    }

    if (null != value && customIpHeaderName != null && customIpHeaderName.equalsIgnoreCase(key)) {
      getHeaders().customIpHeader = value;
      return true;
    }

    if (value == null || !collectIpHeaders) {
      return false;
    }

    // May be ends with 'ip' ?
    char last = Character.toLowerCase(key.charAt(key.length() - 1));
    if (last == 'p') {
      if (X_CLUSTER_CLIENT_IP_KEY.equalsIgnoreCase(key)) {
        getHeaders().xClusterClientIp = value;
        return true;
      }
      if (X_REAL_IP_KEY.equalsIgnoreCase(key)) {
        getHeaders().xRealIp = value;
        return true;
      }
      if (CLIENT_IP_KEY.equalsIgnoreCase(key)) {
        getHeaders().clientIp = value;
        return true;
      }
      if (TRUE_CLIENT_IP_KEY.equalsIgnoreCase(key)) {
        getHeaders().trueClientIp = value;
        return true;
      }
    }

    if (VIA_KEY.equalsIgnoreCase(key)) {
      getHeaders().via = value;
      return true;
    }
    return false;
  }

  public ContextInterpreter reset() {
    traceId = DDTraceId.ZERO;
    spanId = DDSpanId.ZERO;
    samplingPriority = defaultSamplingPriority();
    origin = null;
    endToEndStartTime = 0;
    tags = Collections.emptyMap();
    baggage = Collections.emptyMap();
    valid = true;
    httpHeaders = null;
    collectIpHeaders =
        this.clientIpWithoutAppSec
            || this.clientIpResolutionEnabled && ActiveSubsystems.APPSEC_ACTIVE;
    return this;
  }

  TagContext build() {
    if (valid) {
      if (!DDTraceId.ZERO.equals(traceId)) {
        final ExtractedContext context;
        context =
            new ExtractedContext(
                traceId,
                spanId,
                samplingPriority,
                origin,
                endToEndStartTime,
                baggage,
                tags,
                httpHeaders,
                datadogTags);
        return context;
      } else if (origin != null || !tags.isEmpty() || httpHeaders != null) {
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

  private final TagContext.HttpHeaders getHeaders() {
    if (httpHeaders == null) {
      httpHeaders = new TagContext.HttpHeaders();
    }
    return httpHeaders;
  }
}
