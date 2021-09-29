package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import datadog.trace.api.gateway.RequestContext;
import java.util.Collections;
import java.util.Map;

/**
 * When calling extract, we allow for grabbing other configured headers as tags. Those tags are
 * returned here even if the rest of the request would have returned null.
 */
public class TagContext implements AgentSpan.Context.Extracted {

  public static final TagContext empty() {
    return new TagContext(null, null, null, null, null, null, null);
  }

  private final String origin;
  private final String forwarded;
  private final String forwardedProto;
  private final String forwardedHost;
  private final String forwardedIp;
  private final String forwardedPort;
  private final Map<String, String> tags;
  private RequestContext<Object> requestContext;

  public TagContext(
      final String origin,
      String forwarded,
      String forwardedProto,
      String forwardedHost,
      String forwardedIp,
      String forwardedPort,
      final Map<String, String> tags) {
    this.origin = origin;
    this.forwarded = forwarded;
    this.forwardedProto = forwardedProto;
    this.forwardedHost = forwardedHost;
    this.forwardedIp = forwardedIp;
    this.forwardedPort = forwardedPort;
    this.tags = tags;
  }

  public String getOrigin() {
    return origin;
  }

  public String getForwarded() {
    return forwarded;
  }

  @Override
  public String getForwardedProto() {
    return forwardedProto;
  }

  public String getForwardedHost() {
    return forwardedHost;
  }

  @Override
  public String getForwardedIp() {
    return forwardedIp;
  }

  @Override
  public String getForwardedPort() {
    return forwardedPort;
  }

  public Map<String, String> getTags() {
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
  public AgentTrace getTrace() {
    return AgentTracer.NoopAgentTrace.INSTANCE;
  }

  public RequestContext<Object> getRequestContext() {
    return requestContext;
  }

  public TagContext withRequestContext(RequestContext<Object> requestContext) {
    this.requestContext = requestContext;
    return this;
  }
}
