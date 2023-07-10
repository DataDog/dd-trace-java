package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.CF_CONNECTING_IP_KEY;
import static datadog.trace.core.propagation.HttpCodec.CF_CONNECTING_IP_V6_KEY;
import static datadog.trace.core.propagation.HttpCodec.FASTLY_CLIENT_IP_KEY;
import static datadog.trace.core.propagation.HttpCodec.FORWARDED_FOR_KEY;
import static datadog.trace.core.propagation.HttpCodec.FORWARDED_KEY;
import static datadog.trace.core.propagation.HttpCodec.TRUE_CLIENT_IP_KEY;
import static datadog.trace.core.propagation.HttpCodec.USER_AGENT_KEY;
import static datadog.trace.core.propagation.HttpCodec.X_CLIENT_IP_KEY;
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
import datadog.trace.api.TraceConfig;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public abstract class ContextInterpreter implements AgentPropagation.KeyClassifier {
  private TraceConfig traceConfig;

  protected Map<String, String> headerTags;
  protected Map<String, String> baggageMapping;

  protected DDTraceId traceId;
  protected long spanId;
  protected int samplingPriority;
  protected Map<String, String> tags;
  protected Map<String, String> baggage;

  protected CharSequence origin;
  protected long endToEndStartTime;
  protected boolean valid;
  protected boolean fullContext;
  protected PropagationTags propagationTags;

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

  protected ContextInterpreter(Config config) {
    this.customIpHeaderName = config.getTraceClientIpHeader();
    this.clientIpResolutionEnabled = config.isTraceClientIpResolverEnabled();
    this.clientIpWithoutAppSec = config.isClientIpEnabled();
  }

  public interface Factory {
    ContextInterpreter create();
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
      if (X_CLIENT_IP_KEY.equalsIgnoreCase(key)) {
        getHeaders().xClientIp = value;
        return true;
      }
      if (TRUE_CLIENT_IP_KEY.equalsIgnoreCase(key)) {
        getHeaders().trueClientIp = value;
        return true;
      }
      if (FASTLY_CLIENT_IP_KEY.equalsIgnoreCase(key)) {
        getHeaders().fastlyClientIp = value;
        return true;
      }
      if (CF_CONNECTING_IP_KEY.equalsIgnoreCase(key)) {
        getHeaders().cfConnectingIp = value;
        return true;
      }
    } else if (CF_CONNECTING_IP_V6_KEY.equalsIgnoreCase(key)) {
      getHeaders().cfConnectingIpv6 = value;
      return true;
    }

    return false;
  }

  protected final boolean handleTags(String key, String value) {
    if (headerTags.isEmpty() || value == null) {
      return false;
    }
    final String lowerCaseKey = toLowerCase(key);
    final String mappedKey = headerTags.get(lowerCaseKey);
    if (null != mappedKey) {
      if (tags.isEmpty()) {
        tags = new TreeMap<>();
      }
      tags.put(mappedKey, HttpCodec.decode(HttpCodec.firstHeaderValue(value)));
      return true;
    }
    return false;
  }

  protected final boolean handleMappedBaggage(String key, String value) {
    if (baggageMapping.isEmpty() || value == null) {
      return false;
    }
    final String lowerCaseKey = toLowerCase(key);
    final String mappedKey = baggageMapping.get(lowerCaseKey);
    if (null != mappedKey) {
      if (baggage.isEmpty()) {
        baggage = new TreeMap<>();
      }
      baggage.put(mappedKey, HttpCodec.decode(value));
      return true;
    }
    return false;
  }

  public ContextInterpreter reset(TraceConfig traceConfig) {
    this.traceConfig = traceConfig;
    traceId = DDTraceId.ZERO;
    spanId = DDSpanId.ZERO;
    samplingPriority = PrioritySampling.UNSET;
    origin = null;
    endToEndStartTime = 0;
    tags = Collections.emptyMap();
    baggage = Collections.emptyMap();
    valid = true;
    fullContext = true;
    httpHeaders = null;
    collectIpHeaders =
        this.clientIpWithoutAppSec
            || this.clientIpResolutionEnabled && ActiveSubsystems.APPSEC_ACTIVE;
    headerTags = traceConfig.getRequestHeaderTags();
    baggageMapping = traceConfig.getBaggageMapping();
    return this;
  }

  protected TagContext build() {
    if (valid) {
      if (fullContext && !DDTraceId.ZERO.equals(traceId)) {
        final ExtractedContext context;
        context =
            new ExtractedContext(
                traceId,
                spanId,
                samplingPriorityOrDefault(traceId, samplingPriority),
                origin,
                endToEndStartTime,
                baggage,
                tags,
                httpHeaders,
                propagationTags,
                traceConfig);
        return context;
      } else if (origin != null
          || !tags.isEmpty()
          || httpHeaders != null
          || !baggage.isEmpty()
          || samplingPriority != PrioritySampling.UNSET) {
        return new TagContext(
            origin,
            tags,
            httpHeaders,
            baggage,
            samplingPriorityOrDefault(traceId, samplingPriority),
            traceConfig);
      }
    }
    return null;
  }

  protected void invalidateContext() {
    this.valid = false;
  }

  protected void onlyTagContext() {
    this.fullContext = false;
  }

  protected int defaultSamplingPriority() {
    return PrioritySampling.UNSET;
  }

  private TagContext.HttpHeaders getHeaders() {
    if (httpHeaders == null) {
      httpHeaders = new TagContext.HttpHeaders();
    }
    return httpHeaders;
  }

  private int samplingPriorityOrDefault(DDTraceId traceId, int samplingPriority) {
    return samplingPriority == PrioritySampling.UNSET || DDTraceId.ZERO.equals(traceId)
        ? defaultSamplingPriority()
        : samplingPriority;
  }
}
