package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import java.util.Collections;
import java.util.Map;

/**
 * When calling extract, we allow for grabbing other configured headers as tags. Those tags are
 * returned here even if the rest of the request would have returned null.
 */
public class TagContext implements AgentSpan.Context.Extracted {

  private final String origin;
  private final Map<String, String> tags;
  private Object requestContextData;
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
    return null;
  }

  @Override
  public String getForwardedProto() {
    return null;
  }

  @Override
  public String getForwardedHost() {
    return null;
  }

  @Override
  public String getForwardedIp() {
    return null;
  }

  @Override
  public String getForwardedPort() {
    return null;
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
  public DDId getTraceId() {
    return DDId.ZERO;
  }

  @Override
  public DDId getSpanId() {
    return DDId.ZERO;
  }

  @Override
  public final AgentTrace getTrace() {
    return AgentTracer.NoopAgentTrace.INSTANCE;
  }

  public final Object getRequestContextData() {
    return requestContextData;
  }

  public final TagContext withRequestContextData(Object requestContextData) {
    this.requestContextData = requestContextData;
    return this;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return null;
  }

  public static class HttpHeaders {
    public String forwardedFor;
    public String xForwarded;
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
