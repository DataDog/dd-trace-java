package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import java.util.Collections;
import java.util.Map;

/**
 * When calling extract, we allow for grabbing other configured headers as tags. Those tags are
 * returned here even if the rest of the request would have returned null.
 */
public class TagContext implements AgentSpan.Context.Extracted {

  private final String origin;
  private final Map<String, String> tags;
  private Object requestContextDataAppSec;
  private Object requestContextDataIast;
  private final HttpHeaders httpHeaders;

  public TagContext() {
    this(null, null);
  }

  public TagContext(final String origin, final Map<String, String> tags) {
    this(origin, tags, null);
  }

  public TagContext(final String origin, final Map<String, String> tags, HttpHeaders httpHeaders) {
    this.origin = origin;
    this.tags = tags;
    this.httpHeaders = httpHeaders;
  }

  public final String getOrigin() {
    return origin;
  }

  @Override
  public String getForwarded() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.forwarded;
  }

  @Override
  public String getXForwardedProto() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.xForwardedProto;
  }

  @Override
  public String getXForwardedHost() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.xForwardedHost;
  }

  @Override
  public String getXForwardedPort() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.xForwardedPort;
  }

  @Override
  public String getForwardedFor() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.forwardedFor;
  }

  @Override
  public String getXForwarded() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.xForwarded;
  }

  @Override
  public String getXForwardedFor() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.xForwardedFor;
  }

  @Override
  public String getXClusterClientIp() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.xClusterClientIp;
  }

  @Override
  public String getXRealIp() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.xRealIp;
  }

  @Override
  public String getClientIp() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.clientIp;
  }

  @Override
  public String getUserAgent() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.userAgent;
  }

  @Override
  public String getVia() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.via;
  }

  @Override
  public String getTrueClientIp() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.trueClientIp;
  }

  @Override
  public String getCustomIpHeader() {
    if (httpHeaders == null) {
      return null;
    }
    return httpHeaders.customIpHeader;
  }

  public final Map<String, String> getTags() {
    return tags;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return Collections.emptyList();
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

  @Override
  public PathwayContext getPathwayContext() {
    return null;
  }

  public static class HttpHeaders {
    public String forwardedFor;
    public String xForwarded;
    public String forwarded;
    public String xForwardedProto;
    public String xForwardedHost;
    public String xForwardedPort;
    public String xForwardedFor;
    public String xClusterClientIp;
    public String xRealIp;
    public String clientIp;
    public String userAgent;
    public String via;
    public String trueClientIp;
    public String customIpHeader;
  }
}
