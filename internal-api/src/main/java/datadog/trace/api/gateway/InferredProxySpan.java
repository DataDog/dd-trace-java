package datadog.trace.api.gateway;

import static datadog.context.ContextKey.named;
import static datadog.trace.api.DDTags.SPAN_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.MANUAL_INSTRUMENTATION;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;

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
  static final String PROXY_RESOURCE_PATH = "x-dd-proxy-resource-path";
  static final String PROXY_HTTP_METHOD = "x-dd-proxy-httpmethod";
  static final String PROXY_DOMAIN_NAME = "x-dd-proxy-domain-name";
  static final String STAGE = "x-dd-proxy-stage";
  // Optional tags
  static final String PROXY_ACCOUNT_ID = "x-dd-proxy-account-id";
  static final String PROXY_API_ID = "x-dd-proxy-api-id";
  static final String PROXY_REGION = "x-dd-proxy-region";
  static final Map<String, String> SUPPORTED_PROXIES;
  static final String INSTRUMENTATION_NAME = "inferred_proxy";

  static {
    SUPPORTED_PROXIES = new HashMap<>();
    SUPPORTED_PROXIES.put("aws-apigateway", "aws.apigateway");
    SUPPORTED_PROXIES.put("aws-httpapi", "aws.httpapi");
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
    String resourcePath = header(PROXY_RESOURCE_PATH);
    String domainName = header(PROXY_DOMAIN_NAME);

    AgentSpan span = AgentTracer.get().startSpan(INSTRUMENTATION_NAME, proxy, extracted, startTime);

    // Service: value of x-dd-proxy-domain-name or global config if not found
    String serviceName =
        domainName != null && !domainName.isEmpty() ? domainName : Config.get().getServiceName();
    span.setServiceName(serviceName);

    // Component: aws-apigateway or aws-httpapi
    span.setTag(COMPONENT, proxySystem);

    // Span kind: server
    span.setTag(SPAN_KIND, SPAN_KIND_SERVER);

    // SpanType: web
    span.setTag(SPAN_TYPE, "web");

    // Http.method - value of x-dd-proxy-httpmethod
    span.setTag(HTTP_METHOD, httpMethod);

    // Http.url - https:// + x-dd-proxy-domain-name + x-dd-proxy-path
    span.setTag(HTTP_URL, domainName != null ? "https://" + domainName + path : path);

    // Http.route - value of x-dd-proxy-resource-path (or x-dd-proxy-path as fallback)
    span.setTag(HTTP_ROUTE, resourcePath != null ? resourcePath : path);

    // "stage" - value of x-dd-proxy-stage
    span.setTag("stage", header(STAGE));

    // Optional tags - only set if present
    String accountId = header(PROXY_ACCOUNT_ID);
    if (accountId != null && !accountId.isEmpty()) {
      span.setTag("account_id", accountId);
    }

    String apiId = header(PROXY_API_ID);
    if (apiId != null && !apiId.isEmpty()) {
      span.setTag("apiid", apiId);
    }

    String region = header(PROXY_REGION);
    if (region != null && !region.isEmpty()) {
      span.setTag("region", region);
    }

    // Compute and set dd_resource_key (ARN) if we have region and apiId
    if (region != null && !region.isEmpty() && apiId != null && !apiId.isEmpty()) {
      String arn = computeArn(proxySystem, region, apiId);
      if (arn != null) {
        span.setTag("dd_resource_key", arn);
      }
    }

    // _dd.inferred_span = 1 (indicates that this is an inferred span)
    span.setTag("_dd.inferred_span", 1);

    // Resource Name: <Method> <Route> when route available, else <Method> <Path>
    // Prefer x-dd-proxy-resource-path (route) over x-dd-proxy-path (path)
    // Use MANUAL_INSTRUMENTATION priority to prevent TagInterceptor from overriding
    String routeOrPath = resourcePath != null ? resourcePath : path;
    String resourceName =
        httpMethod != null && routeOrPath != null ? httpMethod + " " + routeOrPath : null;
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

  /**
   * Compute ARN for the API Gateway resource. Format for v1 REST:
   * arn:aws:apigateway:{region}::/restapis/{api-id} Format for v2 HTTP:
   * arn:aws:apigateway:{region}::/apis/{api-id}
   */
  private String computeArn(String proxySystem, String region, String apiId) {
    if (proxySystem == null || region == null || apiId == null) {
      return null;
    }

    // Assume AWS partition (could be extended to support other partitions like aws-cn, aws-us-gov)
    String partition = "aws";

    // Determine resource type based on proxy system
    String resourceType;
    if ("aws-apigateway".equals(proxySystem)) {
      resourceType = "restapis"; // v1 REST API
    } else if ("aws-httpapi".equals(proxySystem)) {
      resourceType = "apis"; // v2 HTTP API
    } else {
      return null; // Unknown proxy type
    }

    return String.format("arn:%s:apigateway:%s::/%s/%s", partition, region, resourceType, apiId);
  }

  public void finish() {
    if (this.span != null) {
      // Copy AppSec tags from root span if needed (distributed tracing scenario)
      copyAppSecTagsFromRoot();

      this.span.finish();
      this.span = null;
    }
  }

  /**
   * Copy AppSec tags from the root span to this inferred proxy span. This is needed when
   * distributed tracing is active, because AppSec sets tags on the absolute root span (via
   * setTagTop), but we need them on the inferred proxy span which may be a child of the upstream
   * root span.
   */
  private void copyAppSecTagsFromRoot() {
    AgentSpan rootSpan = this.span.getLocalRootSpan();

    // If root span is different from this span (distributed tracing case)
    if (rootSpan != null && rootSpan != this.span) {
      // Copy _dd.appsec.enabled metric (always 1 if present)
      Object appsecEnabled = rootSpan.getTag("_dd.appsec.enabled");
      if (appsecEnabled != null) {
        this.span.setMetric("_dd.appsec.enabled", 1);
      }

      // Copy _dd.appsec.json tag (AppSec events)
      Object appsecJson = rootSpan.getTag("_dd.appsec.json");
      if (appsecJson != null) {
        this.span.setTag("_dd.appsec.json", appsecJson.toString());
      }
    }
  }

  @Override
  public Context storeInto(@Nonnull Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
