package datadog.trace.lambda;

import static datadog.trace.api.gateway.Events.EVENTS;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles AppSec processing for AWS Lambda invocations.
 * Extracts Lambda event data and invokes AppSec gateway callbacks.
 */
public class LambdaAppSecHandler {

  private static final Logger log = LoggerFactory.getLogger(LambdaAppSecHandler.class);

  /**
   * Process AppSec request data at the start of a Lambda invocation.
   * Extract event data and invokes all relevant AppSec gateway callbacks.
   *
   * @param event the Lambda event object
   * @return AgentSpanContext containing AppSec data, or null if AppSec is disabled or processing fails
   */
  public static AgentSpanContext processRequestStart(Object event) {
    if (!ActiveSubsystems.APPSEC_ACTIVE) {
      log.debug("AppSec is not active, skipping request start processing");
      return null;
    }

    if (!(event instanceof ByteArrayInputStream)) {
      log.debug("Event is not a ByteArrayInputStream, type: {}", event != null ? event.getClass().getName() : "null");
      return null;
    }

    try {
      LambdaEventData eventData = extractEventData((ByteArrayInputStream) event);
      return processAppSecRequestData(eventData);
    } catch (Exception e) {
      log.error("Failed to process AppSec request data", e);
      return null;
    }
  }

  /**
   * Invokes the requestEnded gateway callback to add AppSec data to the span.
   *
   * @param span the current span
   */
  public static void processRequestEnd(AgentSpan span) {
    if (!ActiveSubsystems.APPSEC_ACTIVE || span == null) {
      return;
    }

    RequestContext requestContext = span.getRequestContext();
    if (requestContext != null) {
      AgentTracer.TracerAPI tracer = AgentTracer.get();
      BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCallback =
          tracer.getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.requestEnded());
      if (requestEndedCallback != null) {
        requestEndedCallback.apply(requestContext, span);
      } else {
        log.warn("requestEnded callback is null");
      }
    }
  }

  /**
   * Merge AppSec context data into extension context.
   *
   * @param extensionContext context from extension
   * @param appSecContext context containing AppSec data
   * @return merged context
   */
  public static AgentSpanContext mergeContexts(
      AgentSpanContext extensionContext, AgentSpanContext appSecContext) {
    if (appSecContext == null) {
      return extensionContext;
    }
    if (extensionContext == null) {
      return appSecContext;
    }

    if (appSecContext instanceof TagContext) {
      TagContext extracted = (TagContext) appSecContext;
      Object appSecData = extracted.getRequestContextDataAppSec();
      Object iastData = extracted.getRequestContextDataIast();

      if (extensionContext instanceof TagContext) {
        TagContext merged = (TagContext) extensionContext;
        if (appSecData != null) {
          merged.withRequestContextDataAppSec(appSecData);
        }
        if (iastData != null) {
          merged.withRequestContextDataIast(iastData);
        }
        return merged;
      }

      log.warn(
          "Cannot merge AppSec data: extension context is not a TagContext: {}",
          extensionContext.getClass());
    }
    return extensionContext;
  }

  private static AgentSpanContext processAppSecRequestData(LambdaEventData eventData) {
    AgentTracer.TracerAPI tracer = AgentTracer.get();
    Supplier<Flow<Object>> requestStartedCallback =
        tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestStarted());
    if (requestStartedCallback == null) {
      log.warn("requestStarted callback is null");
      return null;
    }

    TagContext tagContext = new TagContext();
    Object appSecRequestContext;

    // Call requestStarted
    appSecRequestContext = requestStartedCallback.get().getResult();
    tagContext.withRequestContextDataAppSec(appSecRequestContext);

    if (appSecRequestContext != null) {
      TemporaryRequestContext requestContext = new TemporaryRequestContext(appSecRequestContext);

      // Call requestMethodUriRaw
      if (eventData.method != null && eventData.path != null) {
        datadog.trace.api.function.TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> methodUriCallback =
            tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestMethodUriRaw());
        if (methodUriCallback != null) {
          LambdaURIDataAdapter uriAdapter = new LambdaURIDataAdapter(eventData.path);
          methodUriCallback.apply(requestContext, eventData.method, uriAdapter);
        } else {
          log.warn("requestMethodUriRaw callback is null");
        }
      }

      // Call requestHeader for each header
      if (eventData.headers != null && !eventData.headers.isEmpty()) {
        TriConsumer<RequestContext, String, String> headerCallback =
            tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestHeader());
        if (headerCallback != null) {
          for (Map.Entry<String, String> header : eventData.headers.entrySet()) {
            headerCallback.accept(requestContext, header.getKey(), header.getValue());
          }
        } else {
          log.warn("requestHeader callback is null");
        }
      }

      // Call requestClientSocketAddress
      if (eventData.sourceIp != null) {
        datadog.trace.api.function.TriFunction<RequestContext, String, Integer, Flow<Void>> socketAddrCallback =
            tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestClientSocketAddress());
        if (socketAddrCallback != null) {
          Integer port = eventData.sourcePort != null ? eventData.sourcePort : 0;
          socketAddrCallback.apply(requestContext, eventData.sourceIp, port);
        } else {
          log.warn("requestClientSocketAddress callback is null");
        }
      }

      // Call requestHeaderDone
      Function<RequestContext, Flow<Void>> headerDoneCallback =
          tracer
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.requestHeaderDone());
      if (headerDoneCallback != null) {
        headerDoneCallback.apply(requestContext);
      } else {
        log.warn("requestHeaderDone callback is null");
      }

      // Call requestPathParams
      if (eventData.pathParameters != null && !eventData.pathParameters.isEmpty()) {
        BiFunction<RequestContext, Map<String, ?>, Flow<Void>> pathParamsCallback =
            tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestPathParams());
        if (pathParamsCallback != null) {
          pathParamsCallback.apply(requestContext, eventData.pathParameters);
        } else {
          log.warn("requestPathParams callback is null");
        }
      }

      // Call requestBodyProcessed
      if (eventData.body != null) {
        BiFunction<RequestContext, Object, Flow<Void>> bodyCallback =
            tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestBodyProcessed());
        if (bodyCallback != null) {
          bodyCallback.apply(requestContext, eventData.body);
        } else {
          log.warn("requestBodyProcessed callback is null");
        }
      }
    }
    return tagContext;
  }

  private static LambdaEventData extractEventData(ByteArrayInputStream inputStream)
      throws IOException {
    try {
      StringBuilder jsonBuilder = new StringBuilder(inputStream.available());
      try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
        char[] buffer = new char[1024];
        int charsRead;
        while ((charsRead = reader.read(buffer)) != -1) {
          jsonBuilder.append(buffer, 0, charsRead);
        }
      }
      return extractEventDataFromJson(jsonBuilder.toString());
    } finally {
      inputStream.reset();
    }
  }

  private static LambdaEventData extractEventDataFromJson(String json) {
    try {
      // Parse JSON into a Map
      JsonAdapter<Map> adapter =
          new Moshi.Builder().build().adapter(Map.class);

      Map<String, Object> event = adapter.fromJson(json);
      log.debug("Event JSON parsed successfully");

      if (event == null) {
        return new LambdaEventData(Collections.emptyMap(), null, null, null, null, LambdaTriggerType.UNKNOWN, Collections.emptyMap(), null);
      }

      // Detect trigger type
      LambdaTriggerType triggerType = detectTriggerType(event);
      log.debug("Detected Lambda trigger type: {}", triggerType);

      // Extract data based on trigger type
      switch (triggerType) {
        case API_GATEWAY_V1_REST:
          return extractApiGatewayV1Data(event);
        case API_GATEWAY_V2_HTTP:
        case LAMBDA_URL:
          return extractApiGatewayV2HttpData(event, triggerType);
        case API_GATEWAY_V2_WEBSOCKET:
          return extractApiGatewayV2WebSocketData(event);
        case ALB:
        case ALB_MULTI_VALUE:
          return extractAlbData(event, triggerType);
        default:
          log.debug("Unknown trigger type, attempting generic extraction");
          return extractGenericData(event);
      }
    } catch (Exception e) {
      log.error("Failed to parse event data from JSON", e);
      return new LambdaEventData(Collections.emptyMap(), null, null, null, null, LambdaTriggerType.UNKNOWN, Collections.emptyMap(), null);
    }
  }

  private static LambdaTriggerType detectTriggerType(Map<String, Object> event) {
    Object requestContextObj = event.get("requestContext");

    if (requestContextObj instanceof Map) {
      Map<?, ?> requestContext = (Map<?, ?>) requestContextObj;

      // Check for ALB trigger (has elb object)
      if (requestContext.containsKey("elb")) {
        // Check if event has multiValueHeaders
        if (event.containsKey("multiValueHeaders")) {
          return LambdaTriggerType.ALB_MULTI_VALUE;
        }
        return LambdaTriggerType.ALB;
      }

      // Check for WebSocket
      if (requestContext.containsKey("connectionId") &&
          (requestContext.containsKey("eventType") || requestContext.containsKey("routeKey"))) {
        return LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET;
      }

      // Check for API Gateway v2 format
      Object httpObj = requestContext.get("http");
      if (httpObj instanceof Map) {
        Object domainNameObj = requestContext.get("domainName");
        if (domainNameObj instanceof String) {
          String domainName = (String) domainNameObj;
          if (domainName.contains("lambda-url")) {
            return LambdaTriggerType.LAMBDA_URL;
          } else {
            return LambdaTriggerType.API_GATEWAY_V2_HTTP;
          }
        } else {
          return LambdaTriggerType.LAMBDA_URL;
        }
      }

      // Check for API Gateway v1 REST API
      if (requestContext.containsKey("httpMethod") || requestContext.containsKey("requestId")) {
        return LambdaTriggerType.API_GATEWAY_V1_REST;
      }
    }
    return LambdaTriggerType.UNKNOWN;
  }

  /**
   * Extracts data from API Gateway v1 (REST API) event
   */
  private static LambdaEventData extractApiGatewayV1Data(Map<String, Object> event) {
    Map<String, String> headers = extractHeaders(event.get("headers"));
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Object body = extractBody(event);

    Map<?, ?> requestContext = (Map<?, ?>) event.get("requestContext");
    String method = (String) requestContext.get("httpMethod");
    String path = (String) event.get("path");

    String sourceIp = null;
    Object identityObj = requestContext.get("identity");
    if (identityObj instanceof Map) {
      Map<?, ?> identity = (Map<?, ?>) identityObj;
      sourceIp = (String) identity.get("sourceIp");
    }

    return new LambdaEventData(headers, method, path, sourceIp, null, LambdaTriggerType.API_GATEWAY_V1_REST, pathParameters, body);
  }

  /**
   * Extracts data from API Gateway v2 (HTTP API) or Lambda URL event
   */
  private static LambdaEventData extractApiGatewayV2HttpData(Map<String, Object> event, LambdaTriggerType triggerType) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Object body = extractBody(event);

    Map<?, ?> requestContext = (Map<?, ?>) event.get("requestContext");
    Map<?, ?> http = (Map<?, ?>) requestContext.get("http");

    String method = (String) http.get("method");
    String path = (String) http.get("path");
    String sourceIp = (String) http.get("sourceIp");

    // Extract port if available
    Integer sourcePort = null;
    Object portObj = http.get("sourcePort");
    if (portObj instanceof Number) {
      sourcePort = ((Number) portObj).intValue();
    }

    return new LambdaEventData(headers, method, path, sourceIp, sourcePort, triggerType, pathParameters, body);
  }

  /**
   * Extracts data from API Gateway v2 WebSocket event
   */
  private static LambdaEventData extractApiGatewayV2WebSocketData(Map<String, Object> event) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Object body = extractBody(event);

    Map<?, ?> requestContext = (Map<?, ?>) event.get("requestContext");

    String method = "WEBSOCKET";
    String routeKey = (String) requestContext.get("routeKey");
    String path = routeKey != null ? routeKey : "/";

    String sourceIp = null;
    Object identityObj = requestContext.get("identity");
    if (identityObj instanceof Map) {
      Map<?, ?> identity = (Map<?, ?>) identityObj;
      sourceIp = (String) identity.get("sourceIp");
    }

    return new LambdaEventData(headers, method, path, sourceIp, null, LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET, pathParameters, body);
  }

  /**
   * Extracts data from ALB event (with or without multi-value headers)
   */
  private static LambdaEventData extractAlbData(Map<String, Object> event, LambdaTriggerType triggerType) {
    Map<String, String> headers;

    if (triggerType == LambdaTriggerType.ALB_MULTI_VALUE) {
      // Handle multi-value headers (combine multiple values with comma)
      headers = new java.util.HashMap<>();
      Object multiValueHeadersObj = event.get("multiValueHeaders");
      if (multiValueHeadersObj instanceof Map) {
        Map<?, ?> rawHeaders = (Map<?, ?>) multiValueHeadersObj;
        for (Map.Entry<?, ?> entry : rawHeaders.entrySet()) {
          if (entry.getKey() != null && entry.getValue() != null) {
            String key = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof java.util.List) {
              java.util.List<?> values = (java.util.List<?>) entry.getValue();
              // Join multiple values with comma
              String joinedValue = values.stream()
                  .map(String::valueOf)
                  .collect(java.util.stream.Collectors.joining(", "));
              headers.put(key, joinedValue);
            } else {
              headers.put(key, String.valueOf(entry.getValue()));
            }
          }
        }
      }
    } else {
      headers = extractHeaders(event.get("headers"));
    }

    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Object body = extractBody(event);

    String method = (String) event.get("httpMethod");
    String path = (String) event.get("path");
    String sourceIp = headers.get("x-forwarded-for");

    return new LambdaEventData(headers, method, path, sourceIp, null, triggerType, pathParameters, body);
  }

  /**
   * Generic data extraction for unknown trigger types (fallback)
   */
  private static LambdaEventData extractGenericData(Map<String, Object> event) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Object body = extractBody(event);

    String method = null;
    String path = null;
    String sourceIp = null;

    // Try to extract from requestContext if available
    Object requestContextObj = event.get("requestContext");
    if (requestContextObj instanceof Map) {
      Map<?, ?> requestContext = (Map<?, ?>) requestContextObj;

      Object httpObj = requestContext.get("http");
      if (httpObj instanceof Map) {
        Map<?, ?> http = (Map<?, ?>) httpObj;
        method = (String) http.get("method");
        path = (String) http.get("path");
        sourceIp = (String) http.get("sourceIp");
      } else {
        Object methodObj = requestContext.get("httpMethod");
        if (methodObj != null) {
          method = String.valueOf(methodObj);
        }

        Object identityObj = requestContext.get("identity");
        if (identityObj instanceof Map) {
          Map<?, ?> identity = (Map<?, ?>) identityObj;
          sourceIp = (String) identity.get("sourceIp");
        }
      }
    }

    // Try root level fields
    if (method == null) {
      Object methodObj = event.get("httpMethod");
      if (methodObj != null) {
        method = String.valueOf(methodObj);
      }
    }
    if (path == null) {
      Object pathObj = event.get("path");
      if (pathObj != null) {
        path = String.valueOf(pathObj);
      }
    }

    return new LambdaEventData(headers, method, path, sourceIp, null, LambdaTriggerType.UNKNOWN, pathParameters, body);
  }

  /**
   * Generic helper method to extract string key-value pairs from an object.
   * Converts all keys and values to strings, filtering out null entries.
   */
  private static Map<String, String> extractStringMap(Object mapObj) {
    Map<String, String> result = new java.util.HashMap<>();
    if (mapObj instanceof Map) {
      Map<?, ?> rawMap = (Map<?, ?>) mapObj;
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          String key = String.valueOf(entry.getKey());
          String value = String.valueOf(entry.getValue());
          result.put(key, value);
        }
      }
    }
    return result;
  }

  /**
   * Helper method to extract headers from event
   */
  private static Map<String, String> extractHeaders(Object headersObj) {
    Map<String, String> headers = extractStringMap(headersObj);
    log.debug("Extracted {} headers", headers.size());
    if (headers.containsKey("cookie")) {
      log.debug("Cookie header found with value length: {}", headers.get("cookie").length());
    }
    return headers;
  }

  /**
   * Helper method to extract path parameters from event
   */
  private static Map<String, String> extractPathParameters(Object pathParamsObj) {
    Map<String, String> pathParams = extractStringMap(pathParamsObj);
    log.debug("Extracted {} path parameters", pathParams.size());
    return pathParams;
  }

  /**
   * Helper method to extract and merge headers with cookies array from event.
   * API Gateway v2 provides a separate 'cookies' array that should be merged with headers.
   */
  private static Map<String, String> extractHeadersWithCookies(Map<String, Object> event) {
    Map<String, String> headers = extractHeaders(event.get("headers"));

    // API Gateway v2 provides a pre-parsed cookies array
    Object cookiesObj = event.get("cookies");
    if (cookiesObj instanceof java.util.List) {
      java.util.List<?> cookiesList = (java.util.List<?>) cookiesObj;
      if (!cookiesList.isEmpty()) {
        // Join cookies with "; " separator per RFC 6265
        String cookieValue = cookiesList.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("; "));

        // Merge with existing cookie header if present
        String existingCookie = headers.get("cookie");
        if (existingCookie != null && !existingCookie.isEmpty()) {
          headers.put("cookie", existingCookie + "; " + cookieValue);
        } else {
          headers.put("cookie", cookieValue);
        }
      }
    }

    return headers;
  }

  /**
   * Helper method to extract and parse body from event
   */
  private static Object extractBody(Map<String, Object> event) {
    Object bodyObj = event.get("body");
    if (bodyObj == null) {
      return null;
    }

    String bodyString = String.valueOf(bodyObj);

    // Check if body is base64 encoded (API Gateway feature)
    Boolean isBase64Encoded = (Boolean) event.get("isBase64Encoded");
    if (Boolean.TRUE.equals(isBase64Encoded)) {
      try {
        bodyString = new String(Base64.getDecoder().decode(bodyString), StandardCharsets.UTF_8);
      } catch (Exception e) {
        log.debug("Failed to decode base64 body", e);
        return null;
      }
    }

    // Try to parse as JSON
    Object parsedBody = parseBodyAsJson(bodyString);
    if (parsedBody != null) {
      log.debug("Body parsed as JSON successfully");
      return parsedBody;
    }

    // If not JSON, return the raw string
    log.debug("Body is not JSON, returning raw string");
    return bodyString;
  }

  /**
   * Helper method to parse body as JSON
   */
  private static Object parseBodyAsJson(String body) {
    if (body == null || body.isEmpty() || "null".equals(body)) {
      return null;
    }

    try {
      JsonAdapter<Object> adapter = new Moshi.Builder().build().adapter(Object.class);
      Object parsed = adapter.fromJson(body);
      return parsed;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Temporary RequestContext implementation to hold AppSecRequestContext
   * before a span is created.
   */
  private static class TemporaryRequestContext implements RequestContext {
    private final Object appSecRequestContext;

    TemporaryRequestContext(Object appSecRequestContext) {
      this.appSecRequestContext = appSecRequestContext;
    }

    @Override
    public <T> T getData(RequestContextSlot slot) {
      if (slot == RequestContextSlot.APPSEC) {
        return (T) appSecRequestContext;
      }
      return null;
    }

    @Override
    public TraceSegment getTraceSegment() {
      return TraceSegment.NoOp.INSTANCE;
    }

    @Override
    public void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {
      // No-op for temporary context
    }

    @Override
    public BlockResponseFunction getBlockResponseFunction() {
      return null;
    }

    @Override
    public <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
      return null;
    }

    @Override
    public void close() {
      // No-op for temporary context
    }
  }

  /**
   * Enum representing different AWS Lambda trigger types
   */
  private enum LambdaTriggerType {
    API_GATEWAY_V1_REST,      // API Gateway REST API (v1)
    API_GATEWAY_V2_HTTP,      // API Gateway HTTP API (v2)
    API_GATEWAY_V2_WEBSOCKET, // API Gateway WebSocket
    ALB,                      // Application Load Balancer
    ALB_MULTI_VALUE,          // ALB with multi-value headers
    LAMBDA_URL,               // Lambda Function URL
    UNKNOWN                   // Unknown or unsupported trigger
  }

  /**
   * Object for Lambda event data needed for AppSec processing
   */
  private static class LambdaEventData {
    final Map<String, String> headers;
    final String method;
    final String path;
    final String sourceIp;
    final Integer sourcePort;
    final LambdaTriggerType triggerType;
    final Map<String, String> pathParameters;
    final Object body;

    LambdaEventData(Map<String, String> headers, String method, String path, String sourceIp, Integer sourcePort, LambdaTriggerType triggerType, Map<String, String> pathParameters, Object body) {
      this.headers = headers;
      this.method = method;
      this.path = path;
      this.sourceIp = sourceIp;
      this.sourcePort = sourcePort;
      this.triggerType = triggerType;
      this.pathParameters = pathParameters;
      this.body = body;
    }
  }

  /**
   * URIDataAdapter implementation for Lambda events.
   */
  private static class LambdaURIDataAdapter extends URIDataAdapterBase {
    private final String path;
    private final String query;

    LambdaURIDataAdapter(String pathWithQuery) {
      if (pathWithQuery != null) {
        int queryIndex = pathWithQuery.indexOf('?');
        if (queryIndex != -1) {
          this.path = pathWithQuery.substring(0, queryIndex);
          this.query = pathWithQuery.substring(queryIndex + 1);
        } else {
          this.path = pathWithQuery;
          this.query = null;
        }
      } else {
        this.path = "/";
        this.query = null;
      }
    }

    @Override
    public String scheme() {
      return "https";
    }

    @Override
    public String host() {
      return null;
    }

    @Override
    public int port() {
      return 443;
    }

    @Override
    public String path() {
      return path;
    }

    @Override
    public String fragment() {
      return null;
    }

    @Override
    public String query() {
      return query;
    }

    @Override
    public boolean supportsRaw() {
      return true;
    }

    @Override
    public String rawPath() {
      return path;
    }

    @Override
    public String rawQuery() {
      return query;
    }
  }
}
