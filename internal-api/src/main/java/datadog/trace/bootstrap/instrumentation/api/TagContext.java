package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.sampling.PrioritySampling;
import java.util.Collections;
import java.util.Map;

/**
 * When calling extract, we allow for grabbing other configured headers as tags. Those tags are
 * returned here even if the rest of the request would have returned null.
 */
public class TagContext implements AgentSpan.Context.Extracted {

  private static final HttpHeaders EMPTY_HTTP_HEADERS = new HttpHeaders();

  private final CharSequence origin;
  private final Map<String, String> tags;
  private Object requestContextDataAppSec;
  private Object requestContextDataIast;
  private Object ciVisibilityContextData;
  private PathwayContext pathwayContext;
  private final HttpHeaders httpHeaders;
  private final Map<String, String> baggage;
  private final int samplingPriority;
  private final TraceConfig traceConfig;

  public TagContext() {
    this(null, null);
  }

  public TagContext(final String origin, final Map<String, String> tags) {
    this(origin, tags, null, null, PrioritySampling.UNSET, null);
  }

  public TagContext(
      final CharSequence origin,
      final Map<String, String> tags,
      final HttpHeaders httpHeaders,
      final Map<String, String> baggage,
      final int samplingPriority,
      final TraceConfig traceConfig) {
    this.origin = origin;
    this.tags = tags;
    this.httpHeaders = httpHeaders == null ? EMPTY_HTTP_HEADERS : httpHeaders;
    this.baggage = baggage == null ? Collections.emptyMap() : baggage;
    this.samplingPriority = samplingPriority;
    this.traceConfig = traceConfig;
  }

  public TraceConfig getTraceConfig() {
    return traceConfig;
  }

  public final CharSequence getOrigin() {
    return origin;
  }

  @Override
  public String getForwarded() {
    return httpHeaders.forwarded;
  }

  @Override
  public String getFastlyClientIp() {
    return httpHeaders.fastlyClientIp;
  }

  @Override
  public String getCfConnectingIp() {
    return httpHeaders.cfConnectingIp;
  }

  @Override
  public String getCfConnectingIpv6() {
    return httpHeaders.cfConnectingIpv6;
  }

  @Override
  public String getXForwardedProto() {
    return httpHeaders.xForwardedProto;
  }

  @Override
  public String getXForwardedHost() {
    return httpHeaders.xForwardedHost;
  }

  @Override
  public String getXForwardedPort() {
    return httpHeaders.xForwardedPort;
  }

  @Override
  public String getForwardedFor() {
    return httpHeaders.forwardedFor;
  }

  @Override
  public String getXForwarded() {
    return httpHeaders.xForwarded;
  }

  @Override
  public String getXForwardedFor() {
    return httpHeaders.xForwardedFor;
  }

  @Override
  public String getXClusterClientIp() {
    return httpHeaders.xClusterClientIp;
  }

  @Override
  public String getXRealIp() {
    return httpHeaders.xRealIp;
  }

  @Override
  public String getXClientIp() {
    return httpHeaders.xClientIp;
  }

  @Override
  public String getUserAgent() {
    return httpHeaders.userAgent;
  }

  @Override
  public String getTrueClientIp() {
    return httpHeaders.trueClientIp;
  }

  @Override
  public String getCustomIpHeader() {
    return httpHeaders.customIpHeader;
  }

  public final Map<String, String> getTags() {
    return tags;
  }

  @Override
  public final int getSamplingPriority() {
    return samplingPriority;
  }

  public final Map<String, String> getBaggage() {
    return baggage;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return baggage.entrySet();
  }

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  @Override
  public final AgentTrace getTrace() {
    return AgentTracer.NoopAgentTrace.INSTANCE;
  }

  public final Object getRequestContextDataAppSec() {
    return requestContextDataAppSec;
  }

  public final TagContext withRequestContextDataAppSec(Object requestContextData) {
    this.requestContextDataAppSec = requestContextData;
    return this;
  }

  public final Object getRequestContextDataIast() {
    return requestContextDataIast;
  }

  public final TagContext withRequestContextDataIast(Object requestContextData) {
    this.requestContextDataIast = requestContextData;
    return this;
  }

  public Object getCiVisibilityContextData() {
    return ciVisibilityContextData;
  }

  public TagContext withCiVisibilityContextData(Object ciVisibilityContextData) {
    this.ciVisibilityContextData = ciVisibilityContextData;
    return this;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return this.pathwayContext;
  }

  public TagContext withPathwayContext(PathwayContext pathwayContext) {
    this.pathwayContext = pathwayContext;
    return this;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("TagContext{");
    if (origin != null) {
      builder.append("origin=").append(origin).append(", ");
    }
    if (tags != null) {
      builder.append("tags=").append(tags).append(", ");
    }
    if (baggage != null) {
      builder.append("baggage=").append(baggage).append(", ");
    }
    if (samplingPriority != PrioritySampling.UNSET) {
      builder.append("samplingPriority=").append(samplingPriority).append(", ");
    }
    return builder.append('}').toString();
  }

  public static class HttpHeaders {
    public String fastlyClientIp;
    public String cfConnectingIp;
    public String cfConnectingIpv6;
    public String xForwarded;
    public String forwarded;
    public String xForwardedProto;
    public String xForwardedHost;
    public String xForwardedPort;
    public String xForwardedFor;
    public String forwardedFor;
    public String xClusterClientIp;
    public String xRealIp;
    public String xClientIp;
    public String userAgent;
    public String trueClientIp;
    public String customIpHeader;
  }
}
