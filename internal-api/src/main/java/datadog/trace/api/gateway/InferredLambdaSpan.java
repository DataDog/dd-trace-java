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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InferredLambdaSpan creates synthetic spans for AWS Lambda functions invoked by API Gateway.
 * Similar to InferredProxySpan, but extracts information from Lambda event objects instead of HTTP
 * headers.
 *
 * <p>When a Lambda function is invoked by API Gateway (v1 REST or v2 HTTP), this class detects the
 * invocation type and creates a properly shaped span with tags and metrics conforming to RFC-1081
 * Appendix A.
 */
public class InferredLambdaSpan implements ImplicitContextKeyed {
  private static final Logger log = LoggerFactory.getLogger(InferredLambdaSpan.class);
  private static final ContextKey<InferredLambdaSpan> CONTEXT_KEY = named("inferred-lambda-key");
  static final Map<String, String> SUPPORTED_PROXIES;
  static final String INSTRUMENTATION_NAME = "inferred_lambda";

  // Environment variable to control aws_user tag emission (privacy guard)
  private static final String AWS_USER_TAG_ENABLED_ENV = "DD_LAMBDA_INFERRED_SPAN_AWS_USER_ENABLED";

  static {
    SUPPORTED_PROXIES = new HashMap<>();
    SUPPORTED_PROXIES.put("aws-apigateway", "aws.apigateway");
    SUPPORTED_PROXIES.put("aws-httpapi", "aws.httpapi");
  }

  private final Object event;
  private AgentSpan span;
  private int apiGatewayVersion; // 0 = not API Gateway, 1 = REST v1, 2 = HTTP v2

  // Cached extracted fields
  private String httpMethod;
  private String path;
  private String domainName;
  private String stage;
  private String apiId;
  private String accountId;
  private String region;
  private String userArn;
  private String resourcePath;

  /**
   * Create an InferredLambdaSpan from a Lambda event object.
   *
   * @param event The Lambda input event (may be APIGatewayProxyRequestEvent or
   *     APIGatewayV2HTTPEvent)
   * @return InferredLambdaSpan instance
   */
  public static InferredLambdaSpan fromEvent(Object event) {
    return new InferredLambdaSpan(event);
  }

  /**
   * Retrieve InferredLambdaSpan from context.
   *
   * @param context The context to retrieve from
   * @return InferredLambdaSpan or null if not present
   */
  public static InferredLambdaSpan fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  private InferredLambdaSpan(Object event) {
    this.event = event;
    this.apiGatewayVersion = detectApiGatewayVersion();
    if (isValid()) {
      extractFields();
    }
  }

  /**
   * Check if this event represents a valid API Gateway invocation.
   *
   * @return true if this is a valid API Gateway v1 or v2 event
   */
  public boolean isValid() {
    return apiGatewayVersion > 0;
  }

  /**
   * Detect API Gateway version from event structure using reflection.
   *
   * <p>API Gateway v1 (REST): Has requestContext.requestId and requestContext.apiId API Gateway v2
   * (HTTP): Has requestContext.http and version="2.0"
   *
   * @return 0 if not API Gateway, 1 for REST API v1, 2 for HTTP API v2
   */
  private int detectApiGatewayVersion() {
    if (event == null) {
      return 0;
    }

    try {
      // Check for v2 first (has "version" field = "2.0")
      Object version = getEventField("version");
      if (version != null && "2.0".equals(version.toString())) {
        // Verify requestContext.http exists (v2 specific)
        Object requestContext = getEventField("requestContext");
        if (requestContext != null) {
          Object http = getNestedField(requestContext, "http");
          if (http != null) {
            log.debug("Detected API Gateway HTTP API v2 event");
            return 2;
          }
        }
      }

      // Check for v1 (has requestContext.requestId and requestContext.apiId)
      Object requestContext = getEventField("requestContext");
      if (requestContext != null) {
        Object requestId = getNestedField(requestContext, "requestId");
        Object apiId = getNestedField(requestContext, "apiId");
        if (requestId != null && apiId != null) {
          log.debug("Detected API Gateway REST API v1 event");
          return 1;
        }
      }

      log.debug("Event is not an API Gateway invocation");
      return 0;
    } catch (Exception e) {
      log.debug("Error detecting API Gateway version from event", e);
      return 0;
    }
  }

  /**
   * Get a field value from the event object using reflection.
   *
   * @param fieldName The field name to retrieve
   * @return The field value or null if not found/accessible
   */
  private Object getEventField(String fieldName) {
    return getNestedField(event, fieldName);
  }

  /**
   * Get a nested field value from an object using reflection. Tries both field access and getter
   * methods.
   *
   * @param obj The object to extract from
   * @param fieldName The field name to retrieve
   * @return The field value or null if not found/accessible
   */
  private Object getNestedField(Object obj, String fieldName) {
    if (obj == null || fieldName == null) {
      return null;
    }

    try {
      // Try getter method first (e.g., getFieldName or getFieldname)
      String getterName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
      try {
        Method getter = obj.getClass().getMethod(getterName);
        return getter.invoke(obj);
      } catch (NoSuchMethodException e) {
        // Try field access
        try {
          Field field = obj.getClass().getDeclaredField(fieldName);
          field.setAccessible(true);
          return field.get(obj);
        } catch (NoSuchFieldException ex) {
          // Field not found
          return null;
        }
      }
    } catch (Exception e) {
      log.debug("Error accessing field {} from object {}", fieldName, obj.getClass().getName(), e);
      return null;
    }
  }

  /**
   * Start the inferred lambda span with proper tags and metrics.
   *
   * @param extracted The extracted span context (may be null or from upstream)
   * @return The span context to use as parent for the lambda invocation span
   */
  public AgentSpanContext start(AgentSpanContext extracted) {
    if (this.span != null || !isValid()) {
      return extracted;
    }

    // Determine proxy system based on version
    String proxySystem = apiGatewayVersion == 1 ? "aws-apigateway" : "aws-httpapi";
    String spanName = SUPPORTED_PROXIES.get(proxySystem);

    // Create span (use current time as start - Lambda invocation time)
    AgentSpan span = AgentTracer.get().startSpan(INSTRUMENTATION_NAME, spanName, extracted);

    // Service: value of domainName or global config if not found
    String serviceName =
        domainName != null && !domainName.isEmpty() ? domainName : Config.get().getServiceName();
    span.setServiceName(serviceName);

    // Component: aws-apigateway or aws-httpapi
    span.setTag(COMPONENT, proxySystem);

    // Span kind: server
    span.setTag(SPAN_KIND, SPAN_KIND_SERVER);

    // SpanType: web
    span.setTag(SPAN_TYPE, "web");

    // Http.method - value of httpMethod
    if (httpMethod != null) {
      span.setTag(HTTP_METHOD, httpMethod);
    }

    // Http.url - https://{domainName}{path}
    if (domainName != null && path != null) {
      span.setTag(HTTP_URL, "https://" + domainName + path);
    } else if (path != null) {
      span.setTag(HTTP_URL, path);
    }

    // Http.route - value of resourcePath (or path as fallback)
    String route = resourcePath != null ? resourcePath : path;
    if (route != null) {
      span.setTag(HTTP_ROUTE, route);
    }

    // Stage - value of stage
    if (stage != null) {
      span.setTag("stage", stage);
    }

    // Optional tags - only set if present
    if (accountId != null && !accountId.isEmpty()) {
      span.setTag("account_id", accountId);
    }

    if (apiId != null && !apiId.isEmpty()) {
      span.setTag("apiid", apiId);
    }

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

    // aws_user - optional, guarded by privacy flag
    if (isAwsUserTagEnabled() && userArn != null && !userArn.isEmpty()) {
      span.setTag("aws_user", userArn);
    }

    // Resource Name: <Method> <Route> when route available, else <Method> <Path>
    // Use MANUAL_INSTRUMENTATION priority to prevent TagInterceptor from overriding
    if (httpMethod != null && route != null) {
      String resourceName = httpMethod + " " + route;
      span.setResourceName(resourceName, MANUAL_INSTRUMENTATION);
    }

    // Store span
    this.span = span;

    // Return inferred span as new parent context
    return this.span.context();
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

  /** Finish the inferred lambda span and copy AppSec tags from root span. */
  public void finish() {
    if (this.span != null) {
      // Copy AppSec tags from root span if needed (distributed tracing scenario)
      copyAppSecTagsFromRoot();

      this.span.finish();
      this.span = null;
    }
  }

  /**
   * Copy AppSec tags from the root span to this inferred lambda span. This is needed when
   * distributed tracing is active, because AppSec sets tags on the absolute root span (via
   * setTagTop), but we need them on the inferred lambda span which may be a child of the upstream
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

  /**
   * Store this InferredLambdaSpan in the given context.
   *
   * @param context The context to store into
   * @return Updated context with this span stored
   */
  @Override
  public Context storeInto(@Nonnull Context context) {
    return context.with(CONTEXT_KEY, this);
  }

  /**
   * Get the API Gateway version detected.
   *
   * @return 0 if not API Gateway, 1 for REST v1, 2 for HTTP v2
   */
  public int getApiGatewayVersion() {
    return apiGatewayVersion;
  }

  /**
   * Extract all required fields from the API Gateway event. Field locations differ between v1 and
   * v2.
   */
  private void extractFields() {
    try {
      Object requestContext = getEventField("requestContext");
      if (requestContext == null) {
        log.debug("No requestContext found in event");
        return;
      }

      if (apiGatewayVersion == 1) {
        // API Gateway v1 (REST API)
        extractV1Fields(requestContext);
      } else if (apiGatewayVersion == 2) {
        // API Gateway v2 (HTTP API)
        extractV2Fields(requestContext);
      }

      // Extract domainName from headers (both v1 and v2)
      extractDomainName();

      // Parse region from domainName if available
      if (domainName != null) {
        region = parseRegionFromDomainName(domainName);
      }

      log.debug(
          "Extracted fields: method={}, path={}, domain={}, stage={}, apiId={}, region={}",
          httpMethod,
          path,
          domainName,
          stage,
          apiId,
          region);
    } catch (Exception e) {
      log.debug("Error extracting fields from API Gateway event", e);
    }
  }

  /**
   * Extract fields from API Gateway v1 (REST API) event.
   *
   * @param requestContext The requestContext object
   */
  private void extractV1Fields(Object requestContext) {
    // httpMethod: requestContext.httpMethod
    Object methodObj = getNestedField(requestContext, "httpMethod");
    if (methodObj != null) {
      httpMethod = methodObj.toString();
    }

    // path: requestContext.path
    Object pathObj = getNestedField(requestContext, "path");
    if (pathObj != null) {
      path = pathObj.toString();
    }

    // resourcePath: requestContext.resourcePath
    Object resourcePathObj = getNestedField(requestContext, "resourcePath");
    if (resourcePathObj != null) {
      resourcePath = resourcePathObj.toString();
    }

    // stage: requestContext.stage
    Object stageObj = getNestedField(requestContext, "stage");
    if (stageObj != null) {
      stage = stageObj.toString();
    }

    // apiId: requestContext.apiId
    Object apiIdObj = getNestedField(requestContext, "apiId");
    if (apiIdObj != null) {
      apiId = apiIdObj.toString();
    }

    // accountId: requestContext.accountId
    Object accountIdObj = getNestedField(requestContext, "accountId");
    if (accountIdObj != null) {
      accountId = accountIdObj.toString();
    }

    // userArn: requestContext.identity.userArn
    Object identity = getNestedField(requestContext, "identity");
    if (identity != null) {
      Object userArnObj = getNestedField(identity, "userArn");
      if (userArnObj != null) {
        userArn = userArnObj.toString();
      }
    }
  }

  /**
   * Extract fields from API Gateway v2 (HTTP API) event.
   *
   * @param requestContext The requestContext object
   */
  private void extractV2Fields(Object requestContext) {
    // httpMethod: requestContext.http.method
    Object http = getNestedField(requestContext, "http");
    if (http != null) {
      Object methodObj = getNestedField(http, "method");
      if (methodObj != null) {
        httpMethod = methodObj.toString();
      }

      // path: requestContext.http.path
      Object pathObj = getNestedField(http, "path");
      if (pathObj != null) {
        path = pathObj.toString();
        // v2 uses path as resourcePath as well
        resourcePath = path;
      }
    }

    // stage: requestContext.stage
    Object stageObj = getNestedField(requestContext, "stage");
    if (stageObj != null) {
      stage = stageObj.toString();
    }

    // apiId: requestContext.apiId
    Object apiIdObj = getNestedField(requestContext, "apiId");
    if (apiIdObj != null) {
      apiId = apiIdObj.toString();
    }

    // accountId: requestContext.accountId
    Object accountIdObj = getNestedField(requestContext, "accountId");
    if (accountIdObj != null) {
      accountId = accountIdObj.toString();
    }

    // userArn: requestContext.authentication.iamIdentity.userArn (v2 structure different)
    Object authentication = getNestedField(requestContext, "authentication");
    if (authentication != null) {
      Object iamIdentity = getNestedField(authentication, "iamIdentity");
      if (iamIdentity != null) {
        Object userArnObj = getNestedField(iamIdentity, "userArn");
        if (userArnObj != null) {
          userArn = userArnObj.toString();
        }
      }
    }
  }

  /** Extract domain name from headers.Host field (both v1 and v2 have headers). */
  private void extractDomainName() {
    try {
      Object headers = getEventField("headers");
      if (headers != null) {
        // Try "Host" first
        Object hostObj = getNestedField(headers, "Host");
        if (hostObj == null) {
          // Try lowercase "host"
          hostObj = getNestedField(headers, "host");
        }
        if (hostObj != null) {
          domainName = hostObj.toString();
        }
      }
    } catch (Exception e) {
      log.debug("Error extracting domain name from headers", e);
    }
  }

  /**
   * Parse AWS region from domain name. Format: {api-id}.execute-api.{region}.amazonaws.com
   *
   * @param domain The domain name
   * @return The region or null if not parseable
   */
  private String parseRegionFromDomainName(String domain) {
    if (domain == null || !domain.contains("execute-api")) {
      return null;
    }

    try {
      // Split by dots and find the part after execute-api
      String[] parts = domain.split("\\.");
      for (int i = 0; i < parts.length - 1; i++) {
        if ("execute-api".equals(parts[i])) {
          return parts[i + 1]; // Region is after execute-api
        }
      }
    } catch (Exception e) {
      log.debug("Error parsing region from domain name: {}", domain, e);
    }

    return null;
  }

  /**
   * Check if aws_user tag should be emitted. Controlled by environment variable for privacy
   * concerns.
   *
   * @return true if DD_LAMBDA_INFERRED_SPAN_AWS_USER_ENABLED is set to true
   */
  private boolean isAwsUserTagEnabled() {
    String envValue = System.getenv(AWS_USER_TAG_ENABLED_ENV);
    return "true".equalsIgnoreCase(envValue) || "1".equals(envValue);
  }
}
