package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.api.TracePropagationStyle.NONE;
import static java.util.Collections.emptyList;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * When calling extract, we allow for grabbing other configured headers as tags. Those tags are
 * returned here even if the rest of the request would have returned null.
 */
public class TagContext implements AgentSpanContext.Extracted {

  private static final HttpHeaders EMPTY_HTTP_HEADERS = new HttpHeaders();

  private final CharSequence origin;
  private TagMap tags;
  private List<AgentSpanLink> terminatedContextLinks;
  private Object requestContextDataAppSec;
  private Object requestContextDataIast;
  private Object ciVisibilityContextData;
  private PathwayContext pathwayContext;
  private final HttpHeaders httpHeaders;
  private final Map<String, String> baggage;
  private Baggage w3cBaggage;
  private final int samplingPriority;
  private final TraceConfig traceConfig;
  private final TracePropagationStyle propagationStyle;
  private final DDTraceId traceId;

  public TagContext() {
    this(null, null);
  }

  public TagContext(final CharSequence origin, final TagMap tags) {
    this(origin, tags, null, null, PrioritySampling.UNSET, null, NONE, DDTraceId.ZERO);
  }

  public TagContext(
      final CharSequence origin,
      final TagMap tags,
      final HttpHeaders httpHeaders,
      final Map<String, String> baggage,
      final int samplingPriority,
      final TraceConfig traceConfig,
      final TracePropagationStyle propagationStyle,
      final DDTraceId traceId) {
    this.origin = origin;
    this.tags = tags;
    this.terminatedContextLinks = null;
    this.httpHeaders = httpHeaders == null ? EMPTY_HTTP_HEADERS : httpHeaders;
    this.baggage = baggage == null ? Collections.emptyMap() : baggage;
    this.samplingPriority = samplingPriority;
    this.traceConfig = traceConfig;
    this.propagationStyle = propagationStyle;
    this.traceId = traceId;
  }

  public TraceConfig getTraceConfig() {
    return traceConfig;
  }

  public TracePropagationStyle getPropagationStyle() {
    return this.propagationStyle;
  }

  public final CharSequence getOrigin() {
    return origin;
  }

  @Override
  public List<AgentSpanLink> getTerminatedContextLinks() {
    return this.terminatedContextLinks == null ? emptyList() : this.terminatedContextLinks;
  }

  public void addTerminatedContextLink(AgentSpanLink link) {
    if (this.terminatedContextLinks == null) {
      this.terminatedContextLinks = new ArrayList<>();
    }
    this.terminatedContextLinks.add(link);
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

  public final TagMap getTags() {
    return (this.tags == null) ? TagMap.EMPTY : this.tags;
  }

  public void putTag(final String key, final String value) {
    if (this.tags == null) {
      this.tags = TagMap.create(4);
    }
    this.tags.set(key, value);
  }

  public void putTagIfAbsent(final String key, final String value) {
    if (this.tags == null) {
      this.tags = TagMap.create(4);
    }
    if (!this.tags.containsKey(key)) {
      this.tags.set(key, value);
    }
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

  public final Baggage getW3CBaggage() {
    return w3cBaggage;
  }

  public void setW3CBaggage(Baggage w3cBaggage) {
    this.w3cBaggage = w3cBaggage;
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }

  @Override
  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  @Override
  public final AgentTraceCollector getTraceCollector() {
    return AgentTracer.NoopAgentTraceCollector.INSTANCE;
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

  @Override
  public boolean isRemote() {
    return true;
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
    public String xForwardedProto;
    public String xForwardedHost;
    public String xForwardedPort;
    public String xForwardedFor;
    public String forwarded;
    public String forwardedFor;
    public String xClusterClientIp;
    public String xRealIp;
    public String xClientIp;
    public String userAgent;
    public String trueClientIp;
    public String customIpHeader;
  }
}
