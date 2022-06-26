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

  public TagContext() {
    this(null, null);
  }

  public TagContext(final String origin, final Map<String, String> tags) {
    this.origin = origin;
    this.tags = tags;
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
    return null;
  }

  @Override
  public String getXForwarded() {
    return null;
  }

  @Override
  public String getXForwardedFor() {
    return null;
  }

  @Override
  public String getXClusterClientIp() {
    return null;
  }

  @Override
  public String getXRealIp() {
    return null;
  }

  @Override
  public String getClientIp() {
    return null;
  }

  @Override
  public String getUserAgent() {
    return null;
  }

  @Override
  public String getVia() {
    return null;
  }

  @Override
  public String getTrueClientIp() {
    return null;
  }

  @Override
  public String getCustomIpHeader() {
    return null;
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
}
