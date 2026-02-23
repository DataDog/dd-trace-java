package datadog.trace.api.gateway;

import static datadog.context.ContextKey.named;
import static datadog.trace.api.DDTags.SPAN_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.MANUAL_INSTRUMENTATION;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

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
    String httpMethod = header(PROXY_HTTP_METHOD);
    String path = header(PROXY_PATH);
    String domainName = header(PROXY_DOMAIN_NAME);

    AgentSpan span = AgentTracer.get().startSpan(INSTRUMENTATION_NAME, proxy, extracted, startTime);

    // Service: value of x-dd-proxy-domain-name or global config if not found
    String serviceName =
        domainName != null && !domainName.isEmpty() ? domainName : Config.get().getServiceName();
    span.setServiceName(serviceName, INSTRUMENTATION_NAME);

    // Component: aws-apigateway
    span.setTag(COMPONENT, proxySystem);

    // SpanType: web
    span.setTag(SPAN_TYPE, "web");

    // Http.method - value of x-dd-proxy-httpmethod
    span.setTag(HTTP_METHOD, httpMethod);

    // Http.url - value of x-dd-proxy-domain-name + x-dd-proxy-path
    span.setTag(HTTP_URL, domainName != null ? domainName + path : path);

    // Http.route - value of x-dd-proxy-path
    span.setTag(HTTP_ROUTE, path);

    // "stage" - value of x-dd-proxy-stage
    span.setTag("stage", header(STAGE));

    // _dd.inferred_span = 1 (indicates that this is an inferred span)
    span.setTag("_dd.inferred_span", 1);

    // Resource Name: value of x-dd-proxy-httpmethod + " " + value of x-dd-proxy-path
    // Use MANUAL_INSTRUMENTATION priority to prevent TagInterceptor from overriding
    String resourceName = httpMethod != null && path != null ? httpMethod + " " + path : null;
    if (resourceName != null) {
      span.setResourceName(resourceName, MANUAL_INSTRUMENTATION);
    }

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
  public Context storeInto(@Nonnull Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
