package datadog.trace.api.gateway;

import static datadog.context.ContextKey.named;
import static datadog.trace.api.DDTags.SPAN_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class InferredProxySpan implements ImplicitContextKeyed {
  private static final ContextKey<InferredProxySpan> CONTEXT_KEY = named("inferred-proxy-key");
  static final String PROXY_SYSTEM = "x-dd-proxy";
  static final String PROXY_START_TIME_MS = "x-dd-proxy-request-time-ms";
  static final String PROXY_PATH = "x-dd-proxy-path";
  static final String PROXY_HTTP_METHOD = "x-dd-proxy-httpmethod";
  static final String PROXY_DOMAIN_NAME = "x-dd-proxy-domain-name";
  static final String STAGE = "x-dd-proxy-stage";
  static final Map<String, String> SUPPORTED_PROXIES;
  static final String INSTRUMENTATION_NAME = "inferred_proxy";

  static {
    SUPPORTED_PROXIES = new HashMap<>();
    SUPPORTED_PROXIES.put("aws-apigateway", "aws.apigateway");
  }

  private final Map<String, String> headers;
  private AgentSpan span;

  public static InferredProxySpan fromHeaders(Map<String, String> values) {
    return new InferredProxySpan(values);
  }

  public static InferredProxySpan fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  private InferredProxySpan(Map<String, String> headers) {
    this.headers = headers == null ? Collections.emptyMap() : headers;
  }

  public boolean isValid() {
    String startTimeStr = header(PROXY_START_TIME_MS);
    String proxySystem = header(PROXY_SYSTEM);
    return startTimeStr != null
        && proxySystem != null
        && SUPPORTED_PROXIES.containsKey(proxySystem);
  }

  public AgentSpanContext start(AgentSpanContext extracted) {
    if (this.span != null || !isValid()) {
      return extracted;
    }

    long startTime;
    try {
      startTime = Long.parseLong(header(PROXY_START_TIME_MS)) * 1000; // Convert to microseconds
    } catch (NumberFormatException e) {
      return extracted; // Invalid timestamp
    }

    String proxySystem = header(PROXY_SYSTEM);
    String proxy = SUPPORTED_PROXIES.get(proxySystem);
    AgentSpan span = AgentTracer.get().startSpan(INSTRUMENTATION_NAME, proxy, extracted, startTime);
    span.setServiceName(header(PROXY_DOMAIN_NAME));
    span.setTag(COMPONENT, proxySystem);
    span.setTag(SPAN_TYPE, "web");
    span.setTag(HTTP_METHOD, header(PROXY_HTTP_METHOD));
    span.setTag(HTTP_URL, header(PROXY_DOMAIN_NAME) + header(PROXY_PATH));
    span.setTag("stage", header(STAGE));
    span.setTag("_dd.inferred_span", 1);

    // Free collected headers
    this.headers.clear();
    // Store inferred span
    this.span = span;
    // Return inferred span as new parent context
    return this.span.context();
  }

  private String header(String name) {
    return this.headers.get(name);
  }

  public void finish() {
    if (this.span != null) {
      this.span.finish();
      this.span = null;
    }
  }

  @Override
  public Context storeInto(Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
