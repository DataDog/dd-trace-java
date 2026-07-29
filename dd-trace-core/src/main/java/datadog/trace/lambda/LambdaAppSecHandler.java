package datadog.trace.lambda;

import static datadog.trace.api.gateway.Events.EVENTS;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.logging.RatelimitedLogger;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles AppSec processing for AWS Lambda invocations. Extracts Lambda event data and invokes
 * AppSec gateway callbacks.
 */
public class LambdaAppSecHandler {

  private static final Logger log = LoggerFactory.getLogger(LambdaAppSecHandler.class);
  private static final RatelimitedLogger rlLog = new RatelimitedLogger(log, 5, TimeUnit.MINUTES);

  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<Map> MAP_ADAPTER = MOSHI.adapter(Map.class);
  private static final JsonAdapter<Object> OBJECT_ADAPTER = MOSHI.adapter(Object.class);

  private static final int MAX_EVENT_SIZE = Config.get().getAppSecBodyParsingSizeLimit();

  // Carries the detected trigger type from processRequestStart to processResponseData within the
  // same Lambda invocation. Cleared in processRequestEnd.
  private static final ThreadLocal<LambdaTriggerType> CURRENT_TRIGGER_TYPE = new ThreadLocal<>();

  // Carries the extracted event data from processRequestStart to processRequestEnd, where it is
  // used to set HTTP span tags once the span exists. Cleared in processRequestEnd.
  private static final ThreadLocal<LambdaEventData> CURRENT_EVENT_DATA = new ThreadLocal<>();

  /**
   * Process AppSec request data at the start of a Lambda invocation. Extract event data and invokes
   * all relevant AppSec gateway callbacks.
   *
   * @param event the Lambda event object
   * @return AgentSpanContext containing AppSec data, or null if AppSec is disabled or processing
   *     fails
   */
  public static AgentSpanContext processRequestStart(Object event) {
    if (!ActiveSubsystems.APPSEC_ACTIVE) {
      log.debug("AppSec is not active, skipping request start processing");
      return null;
    }

    CURRENT_TRIGGER_TYPE.set(LambdaTriggerType.UNKNOWN);
    CURRENT_EVENT_DATA.remove();

    if (!(event instanceof ByteArrayInputStream)) {
      log.debug(
          "Event is not a ByteArrayInputStream, type: {}",
          event != null ? event.getClass().getName() : "null");
      // A non-stream event carries no raw HTTP payload AppSec can analyze.
      // Record EMPTY so processRequestEnd marks the span with
      // _dd.appsec.unsupported_event_type.
      CURRENT_EVENT_DATA.set(LambdaEventData.EMPTY);
      return null;
    }

    try {
      LambdaEventData eventData = extractEventData((ByteArrayInputStream) event);
      if (eventData == LambdaEventData.EMPTY) {
        return null;
      }
      CURRENT_TRIGGER_TYPE.set(eventData.triggerType);
      CURRENT_EVENT_DATA.set(eventData);
      if (!isSupportedHttpEvent(eventData)) {
        return null;
      }
      return processAppSecRequestData(eventData);
    } catch (Exception e) {
      log.debug("Failed to process AppSec request data", e);
      return null;
    }
  }

  /**
   * Invokes the requestEnded gateway callback to add AppSec data to the span.
   *
   * @param span the current span
   */
  public static void processRequestEnd(AgentSpan span) {
    LambdaEventData eventData = CURRENT_EVENT_DATA.get();
    CURRENT_TRIGGER_TYPE.remove();
    CURRENT_EVENT_DATA.remove();

    if (!ActiveSubsystems.APPSEC_ACTIVE || span == null) {
      return;
    }

    RequestContext requestContext = span.getRequestContext();
    if (requestContext != null) {
      AgentTracer.TracerAPI tracer = AgentTracer.get();
      BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCallback =
          tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestEnded());
      if (requestEndedCallback != null) {
        requestEndedCallback.apply(requestContext, span);
      } else {
        log.debug("requestEnded callback is null");
      }

      // In Lambda, the WAF runs in processRequestStart before the span exists.
      // GatewayBridge propagates ASM_KEEP based on WAF attack events, but not on
      // isManuallyKept(), which is set by trace-tagging rules that produce no events.
      // Apply it here so those traces are not silently dropped.
      Object rawAppSecCtx = requestContext.getData(RequestContextSlot.APPSEC);
      AppSecContext appSecCtx =
          rawAppSecCtx instanceof AppSecContext ? (AppSecContext) rawAppSecCtx : null;
      if (appSecCtx != null && appSecCtx.isManuallyKept()) {
        TraceSegment traceSeg = requestContext.getTraceSegment();
        traceSeg.setTagTop(Tags.ASM_KEEP, true);
        traceSeg.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      }

      if (eventData != null) {
        if (!isSupportedHttpEvent(eventData)) {
          span.setMetric("_dd.appsec.unsupported_event_type", 1);
        } else {
          applyHttpSpanTags(span, eventData);
        }
      }
    }
  }

  /**
   * Returns true if the event carries enough HTTP-like data (a known trigger type, or a best-effort
   * method/path extracted via generic extraction) for the static HTTP security rules to run
   * against.
   */
  private static boolean isSupportedHttpEvent(LambdaEventData eventData) {
    return eventData.triggerType != LambdaTriggerType.UNKNOWN
        || (eventData.method != null && eventData.path != null);
  }

  /**
   * Sets HTTP span tags (http.url, http.route, http.useragent) derived from the Lambda event.
   */
  private static void applyHttpSpanTags(AgentSpan span, LambdaEventData eventData) {
    if (eventData.method != null && !eventData.method.isEmpty()) {
      span.setTag(Tags.HTTP_METHOD, eventData.method);
    }

    String userAgent = eventData.headers != null ? eventData.headers.get("user-agent") : null;
    if (userAgent != null && !userAgent.isEmpty()) {
      span.setTag(Tags.HTTP_USER_AGENT, userAgent);
    }

    if (eventData.path != null && !eventData.path.isEmpty()) {
      String host = eventData.headers != null ? eventData.headers.get("host") : null;
      String scheme = firstForwardedValue(eventData.headers, "x-forwarded-proto");
      if (scheme == null || scheme.isEmpty()) {
        scheme = "https";
      }
      String url =
          (host != null && !host.isEmpty())
              ? URIUtils.buildURL(scheme, host, 0, eventData.path)
              : eventData.path;
      span.setTag(Tags.HTTP_URL, url);

      if (Config.get().isHttpServerTagQueryString()) {
        String query = buildQueryString(eventData.queryParameters);
        if (query != null && !query.isEmpty()) {
          span.setTag(DDTags.HTTP_QUERY, query);
        }
      }
    }

    if (eventData.route != null && !eventData.route.isEmpty()) {
      span.setTag(Tags.HTTP_ROUTE, eventData.route);
    }
  }

  /**
   * Returns the first value of a potentially comma-separated forwarded header, trimmed (e.g. {@code
   * X-Forwarded-Proto: "https, http"} behind multiple proxies yields {@code "https"}). Returns null
   * if the header is absent.
   */
  private static String firstForwardedValue(Map<String, String> headers, String name) {
    String value = headers != null ? headers.get(name) : null;
    if (value == null) {
      return null;
    }
    int commaIdx = value.indexOf(',');
    return (commaIdx >= 0 ? value.substring(0, commaIdx) : value).trim();
  }

  /**
   * Process response data through WAF before the request context is closed. Extracts status code,
   * headers, and body from the Lambda response and fires the corresponding gateway events.
   *
   * @param span the current span
   * @param result the Lambda handler result (expected to be a ByteArrayOutputStream)
   */
  public static void processResponseData(AgentSpan span, Object result) {
    if (!ActiveSubsystems.APPSEC_ACTIVE
        || span == null
        || !(result instanceof ByteArrayOutputStream)) {
      return;
    }

    try {
      byte[] bytes = ((ByteArrayOutputStream) result).toByteArray();
      if (bytes.length == 0 || bytes.length > MAX_EVENT_SIZE) {
        log.debug(
            "Response size {} exceeds limit {} or is empty, skipping response processing",
            bytes.length,
            MAX_EVENT_SIZE);
        return;
      }

      String json = new String(bytes, StandardCharsets.UTF_8);
      LambdaResponseData responseData = extractResponseData(json);

      // Only process responses for known HTTP trigger types
      LambdaTriggerType triggerType = CURRENT_TRIGGER_TYPE.get();
      if (triggerType == null || !triggerType.isHttp()) {
        return;
      }

      // No statusCode means this is not an API-GW formatted response, or JSON parsing failed.
      // If there is also no other API-GW structure (headers/body), treat the full response as
      // the body. Otherwise (responseData has explicit headers/body fields) keep them and just
      // skip responseStarted below (statusCode remains 0, so the responseStarted guard will not
      // fire).
      if (responseData == null
          || (responseData.statusCode == 0
              && responseData.headers.isEmpty()
              && responseData.body == null)) {
        Object fallbackBody;
        String fallbackContentType;
        try {
          fallbackBody = OBJECT_ADAPTER.fromJson(json);
          fallbackContentType = "application/json";
        } catch (Exception e) {
          fallbackBody = json;
          fallbackContentType = "text/plain";
        }
        Map<String, String> fallbackHeaders =
            Collections.singletonMap("content-type", fallbackContentType);
        responseData = new LambdaResponseData(0, fallbackHeaders, fallbackBody);
      }

      RequestContext requestContext = span.getRequestContext();
      if (requestContext == null) {
        log.debug("Span has no RequestContext, skipping response processing");
        return;
      }

      AgentTracer.TracerAPI tracer = AgentTracer.get();
      CallbackProvider cbp = tracer.getCallbackProvider(RequestContextSlot.APPSEC);

      // Fire response gateway events. Flow results are intentionally ignored: blocking on response
      // is not supported for Lambda because remote config is unavailable in that environment.

      // Fire responseStarted
      if (responseData.statusCode > 0) {
        BiFunction<RequestContext, Integer, Flow<Void>> responseStartedCb =
            cbp.getCallback(EVENTS.responseStarted());
        if (responseStartedCb != null) {
          responseStartedCb.apply(requestContext, responseData.statusCode);
        }
      }

      // Fire responseHeader for each allowed header
      if (responseData.headers != null && !responseData.headers.isEmpty()) {
        TriConsumer<RequestContext, String, String> responseHeaderCb =
            cbp.getCallback(EVENTS.responseHeader());
        if (responseHeaderCb != null) {
          for (Map.Entry<String, String> header : responseData.headers.entrySet()) {
            responseHeaderCb.accept(requestContext, header.getKey(), header.getValue());
          }
        }
      }

      // Fire responseHeaderDone
      Function<RequestContext, Flow<Void>> responseHeaderDoneCb =
          cbp.getCallback(EVENTS.responseHeaderDone());
      if (responseHeaderDoneCb != null) {
        responseHeaderDoneCb.apply(requestContext);
      }

      // Fire responseBody
      if (responseData.body != null) {
        BiFunction<RequestContext, Object, Flow<Void>> responseBodyCb =
            cbp.getCallback(EVENTS.responseBody());
        if (responseBodyCb != null) {
          responseBodyCb.apply(requestContext, responseData.body);
        }
      }
    } catch (Exception e) {
      log.debug("Failed to process AppSec response data", e);
    }
  }

  static LambdaResponseData extractResponseData(String json) {
    try {
      Map<String, Object> response = MAP_ADAPTER.fromJson(json);
      if (response == null) {
        return null;
      }

      // Extract status code
      int statusCode = 0;
      Object statusCodeObj = response.get("statusCode");
      if (statusCodeObj instanceof Number) {
        statusCode = ((Number) statusCodeObj).intValue();
      }

      // Extract headers — keys are lowercased to normalise casing across API GW / ALB variants
      Map<String, String> headers = extractLowercasedStringMap(response.get("headers"));

      // Merge multiValueHeaders if present (API GW v1 / ALB), also lowercasing keys
      Object multiValueHeadersObj = response.get("multiValueHeaders");
      if (multiValueHeadersObj instanceof Map) {
        Map<?, ?> multiValueHeaders = (Map<?, ?>) multiValueHeadersObj;
        for (Map.Entry<?, ?> entry : multiValueHeaders.entrySet()) {
          if (entry.getKey() != null && entry.getValue() instanceof List) {
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            List<?> values = (List<?>) entry.getValue();
            String joinedValue =
                values.stream().map(String::valueOf).collect(Collectors.joining(", "));
            headers.put(key, joinedValue);
          }
        }
      }

      // Extract body
      Object body = null;
      Object bodyObj = response.get("body");
      if (bodyObj != null) {
        String bodyString = String.valueOf(bodyObj);

        // Handle base64 encoding
        Object isBase64EncodedObj = response.get("isBase64Encoded");
        if (Boolean.TRUE.equals(isBase64EncodedObj) || "true".equals(isBase64EncodedObj)) {
          try {
            bodyString = new String(Base64.getDecoder().decode(bodyString), StandardCharsets.UTF_8);
          } catch (Exception e) {
            log.debug("Failed to decode base64 response body", e);
            bodyString = null;
          }
        }

        if (bodyString != null) {
          String contentType = headers.get("content-type");
          body = parseBodyByContentType(bodyString, contentType);
        }
      }

      return new LambdaResponseData(statusCode, headers, body);
    } catch (Exception e) {
      log.debug("Failed to parse response data from JSON", e);
      return null;
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

      if (extensionContext instanceof TagContext) {
        TagContext merged = (TagContext) extensionContext;
        if (appSecData != null) {
          merged.withRequestContextDataAppSec(appSecData);
        }
        return merged;
      }

      rlLog.warn(
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
      log.debug("requestStarted callback is null");
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
        datadog.trace.api.function.TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>
            methodUriCallback =
                tracer
                    .getCallbackProvider(RequestContextSlot.APPSEC)
                    .getCallback(EVENTS.requestMethodUriRaw());
        if (methodUriCallback != null) {
          // Reconstruct full path with query string for AppSec analysis
          String fullPath = buildFullPath(eventData.path, eventData.queryParameters);
          LambdaURIDataAdapter uriAdapter = new LambdaURIDataAdapter(fullPath, eventData.headers);
          methodUriCallback.apply(requestContext, eventData.method, uriAdapter);
        } else {
          log.debug("requestMethodUriRaw callback is null");
        }
      }

      // Call requestHeader for each header
      if (eventData.headers != null && !eventData.headers.isEmpty()) {
        TriConsumer<RequestContext, String, String> headerCallback =
            tracer
                .getCallbackProvider(RequestContextSlot.APPSEC)
                .getCallback(EVENTS.requestHeader());
        if (headerCallback != null) {
          for (Map.Entry<String, String> header : eventData.headers.entrySet()) {
            headerCallback.accept(requestContext, header.getKey(), header.getValue());
          }
        } else {
          log.debug("requestHeader callback is null");
        }
      }

      // Call requestClientSocketAddress
      if (eventData.sourceIp != null) {
        datadog.trace.api.function.TriFunction<RequestContext, String, Integer, Flow<Void>>
            socketAddrCallback =
                tracer
                    .getCallbackProvider(RequestContextSlot.APPSEC)
                    .getCallback(EVENTS.requestClientSocketAddress());
        if (socketAddrCallback != null) {
          Integer port = eventData.sourcePort != null ? eventData.sourcePort : 0;
          socketAddrCallback.apply(requestContext, eventData.sourceIp, port);
        } else {
          log.debug("requestClientSocketAddress callback is null");
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
        log.debug("requestHeaderDone callback is null");
      }

      // Call requestPathParams
      if (eventData.pathParameters != null && !eventData.pathParameters.isEmpty()) {
        BiFunction<RequestContext, Map<String, ?>, Flow<Void>> pathParamsCallback =
            tracer
                .getCallbackProvider(RequestContextSlot.APPSEC)
                .getCallback(EVENTS.requestPathParams());
        if (pathParamsCallback != null) {
          pathParamsCallback.apply(requestContext, eventData.pathParameters);
        } else {
          log.debug("requestPathParams callback is null");
        }
      }

      // Call requestBodyProcessed
      if (eventData.body != null) {
        BiFunction<RequestContext, Object, Flow<Void>> bodyCallback =
            tracer
                .getCallbackProvider(RequestContextSlot.APPSEC)
                .getCallback(EVENTS.requestBodyProcessed());
        if (bodyCallback != null) {
          bodyCallback.apply(requestContext, eventData.body);
        } else {
          log.debug("requestBodyProcessed callback is null");
        }
      }
    }
    return tagContext;
  }

  private static LambdaEventData extractEventData(ByteArrayInputStream inputStream)
      throws IOException {
    inputStream.mark(0);
    try {
      int availableBytes = inputStream.available();

      if (availableBytes <= 0 || availableBytes > MAX_EVENT_SIZE) {
        log.debug(
            "Event size {} exceeds limit {} or is invalid, skipping AppSec processing",
            availableBytes,
            MAX_EVENT_SIZE);
        return LambdaEventData.EMPTY;
      }

      byte[] bytes = new byte[availableBytes];
      int read = inputStream.read(bytes);
      if (read <= 0) {
        return LambdaEventData.EMPTY;
      }
      return extractEventDataFromJson(new String(bytes, 0, read, StandardCharsets.UTF_8));
    } finally {
      inputStream.reset();
    }
  }

  private static LambdaEventData extractEventDataFromJson(String json) {
    try {
      // Parse JSON into a Map
      Map<String, Object> event = MAP_ADAPTER.fromJson(json);
      log.debug("Event JSON parsed successfully");

      if (event == null) {
        return LambdaEventData.EMPTY;
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
      log.debug("Failed to parse event data from JSON", e);
      return LambdaEventData.EMPTY;
    }
  }

  static LambdaTriggerType detectTriggerType(Map<String, Object> event) {
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
      if (requestContext.containsKey("connectionId")
          && (requestContext.containsKey("eventType") || requestContext.containsKey("routeKey"))) {
        return LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET;
      }

      // Check for API Gateway v2 / Lambda Function URL format
      Object httpObj = requestContext.get("http");
      if (httpObj instanceof Map) {
        Object domainNameObj = requestContext.get("domainName");
        if (domainNameObj instanceof String && ((String) domainNameObj).contains("lambda-url")) {
          return LambdaTriggerType.LAMBDA_URL;
        }
        return LambdaTriggerType.API_GATEWAY_V2_HTTP;
      }

      // Check for API Gateway v1 REST API
      if (requestContext.containsKey("httpMethod") || requestContext.containsKey("requestId")) {
        return LambdaTriggerType.API_GATEWAY_V1_REST;
      }
    }
    return LambdaTriggerType.UNKNOWN;
  }

  /** Extracts data from API Gateway v1 (REST API) event */
  private static LambdaEventData extractApiGatewayV1Data(Map<String, Object> event) {
    Map<String, String> headers = extractHeaders(event.get("headers"));
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Map<String, List<String>> queryParameters =
        extractQueryParameters(event.get("queryStringParameters"));
    Object body = extractBody(event, headers);

    Map<?, ?> requestContext = (Map<?, ?>) event.get("requestContext");
    String method = (String) requestContext.get("httpMethod");
    String path = (String) event.get("path");

    String sourceIp = null;
    Object identityObj = requestContext.get("identity");
    if (identityObj instanceof Map) {
      Map<?, ?> identity = (Map<?, ?>) identityObj;
      sourceIp = (String) identity.get("sourceIp");
    }

    String route = (String) event.get("resource");

    return new LambdaEventData(
        headers,
        method,
        path,
        sourceIp,
        null,
        LambdaTriggerType.API_GATEWAY_V1_REST,
        pathParameters,
        queryParameters,
        body,
        route);
  }

  /** Extracts data from API Gateway v2 (HTTP API) or Lambda URL event */
  private static LambdaEventData extractApiGatewayV2HttpData(
      Map<String, Object> event, LambdaTriggerType triggerType) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Map<String, List<String>> queryParameters =
        extractQueryParameters(event.get("queryStringParameters"));
    Object body = extractBody(event, headers);

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

    // routeKey carries the method-prefixed route template for API Gateway v2 HTTP APIs (e.g.
    // "GET /pets/{petId}"). Lambda Function URLs report "$default", which is not a real route.
    // Strip the method prefix so http.route is a bare path template, consistent with the v1 REST
    // extraction above and with HttpResourceDecorator's convention elsewhere in the tracer.
    String route = null;
    if (triggerType == LambdaTriggerType.API_GATEWAY_V2_HTTP) {
      String routeKey = (String) requestContext.get("routeKey");
      if (routeKey != null && !"$default".equals(routeKey)) {
        int spaceIdx = routeKey.indexOf(' ');
        route = spaceIdx >= 0 ? routeKey.substring(spaceIdx + 1) : routeKey;
      }
    }

    return new LambdaEventData(
        headers,
        method,
        path,
        sourceIp,
        sourcePort,
        triggerType,
        pathParameters,
        queryParameters,
        body,
        route);
  }

  /** Extracts data from API Gateway v2 WebSocket event */
  private static LambdaEventData extractApiGatewayV2WebSocketData(Map<String, Object> event) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Map<String, List<String>> queryParameters =
        extractQueryParameters(event.get("queryStringParameters"));
    Object body = extractBody(event, headers);

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

    return new LambdaEventData(
        headers,
        method,
        path,
        sourceIp,
        null,
        LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET,
        pathParameters,
        queryParameters,
        body,
        null);
  }

  /** Extracts data from ALB event (with or without multi-value headers) */
  private static LambdaEventData extractAlbData(
      Map<String, Object> event, LambdaTriggerType triggerType) {
    Map<String, String> headers;

    if (triggerType == LambdaTriggerType.ALB_MULTI_VALUE) {
      // Handle multi-value headers (combine multiple values with comma)
      headers = new HashMap<>();
      Object multiValueHeadersObj = event.get("multiValueHeaders");
      if (multiValueHeadersObj instanceof Map) {
        Map<?, ?> rawHeaders = (Map<?, ?>) multiValueHeadersObj;
        for (Map.Entry<?, ?> entry : rawHeaders.entrySet()) {
          if (entry.getKey() != null && entry.getValue() != null) {
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (entry.getValue() instanceof List) {
              List<?> values = (List<?>) entry.getValue();
              // Join multiple values with comma
              String joinedValue =
                  values.stream().map(String::valueOf).collect(Collectors.joining(", "));
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

    // ALB can have both queryStringParameters and multiValueQueryStringParameters
    Map<String, List<String>> queryParameters;
    if (triggerType == LambdaTriggerType.ALB_MULTI_VALUE) {
      queryParameters =
          extractMultiValueQueryParameters(event.get("multiValueQueryStringParameters"));
    } else {
      queryParameters = extractQueryParameters(event.get("queryStringParameters"));
    }

    Object body = extractBody(event, headers);

    String method = (String) event.get("httpMethod");
    String path = (String) event.get("path");
    // x-forwarded-for may carry a comma-separated proxy chain; take the first (client) hop.
    String sourceIp = firstForwardedValue(headers, "x-forwarded-for");

    return new LambdaEventData(
        headers,
        method,
        path,
        sourceIp,
        null,
        triggerType,
        pathParameters,
        queryParameters,
        body,
        null);
  }

  /** Generic data extraction for unknown trigger types (fallback) */
  private static LambdaEventData extractGenericData(Map<String, Object> event) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Map<String, List<String>> queryParameters =
        extractQueryParameters(event.get("queryStringParameters"));
    Object body = extractBody(event, headers);

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

    return new LambdaEventData(
        headers,
        method,
        path,
        sourceIp,
        null,
        LambdaTriggerType.UNKNOWN,
        pathParameters,
        queryParameters,
        body,
        null);
  }

  /**
   * Generic helper method to extract string key-value pairs from an object. Converts all keys and
   * values to strings, filtering out null entries.
   */
  private static Map<String, String> extractStringMap(Object mapObj) {
    Map<String, String> result = new HashMap<>();
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

  private static Map<String, String> extractLowercasedStringMap(Object mapObj) {
    Map<String, String> rawMap = extractStringMap(mapObj);
    Map<String, String> result = new HashMap<>();
    for (Map.Entry<String, String> entry : rawMap.entrySet()) {
      result.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
    }
    return result;
  }

  private static Map<String, String> extractHeaders(Object headersObj) {
    Map<String, String> headers = extractLowercasedStringMap(headersObj);
    log.debug("Extracted {} headers", headers.size());
    if (headers.containsKey("cookie")) {
      log.debug("Cookie header found with value length: {}", headers.get("cookie").length());
    }
    return headers;
  }

  /** Helper method to extract path parameters from event */
  private static Map<String, String> extractPathParameters(Object pathParamsObj) {
    Map<String, String> pathParams = extractStringMap(pathParamsObj);
    log.debug("Extracted {} path parameters", pathParams.size());
    return pathParams;
  }

  /**
   * Helper method to extract query parameters from event. Converts Map<String, String> to
   * Map<String, List<String>> format expected by AppSec.
   */
  private static Map<String, List<String>> extractQueryParameters(Object queryParamsObj) {
    Map<String, List<String>> result = new HashMap<>();
    if (queryParamsObj instanceof Map) {
      Map<?, ?> rawMap = (Map<?, ?>) queryParamsObj;
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          String key = String.valueOf(entry.getKey());
          String value = String.valueOf(entry.getValue());
          result.put(key, Collections.singletonList(value));
        }
      }
    }
    log.debug("Extracted {} query parameters", result.size());
    return result;
  }

  /**
   * Helper method to extract multi-value query parameters (used by ALB). Handles Map<String,
   * List<String>> format directly.
   */
  private static Map<String, List<String>> extractMultiValueQueryParameters(Object queryParamsObj) {
    Map<String, List<String>> result = new HashMap<>();
    if (queryParamsObj instanceof Map) {
      Map<?, ?> rawMap = (Map<?, ?>) queryParamsObj;
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          String key = String.valueOf(entry.getKey());
          if (entry.getValue() instanceof List) {
            List<?> values = (List<?>) entry.getValue();
            List<String> stringValues = new ArrayList<>();
            for (Object value : values) {
              if (value != null) {
                stringValues.add(String.valueOf(value));
              }
            }
            result.put(key, stringValues);
          } else {
            result.put(key, Collections.singletonList(String.valueOf(entry.getValue())));
          }
        }
      }
    }
    log.debug("Extracted {} multi-value query parameters", result.size());
    return result;
  }

  /**
   * Helper method to build full path including query string. Lambda events provide path and query
   * parameters separately, so we need to reconstruct the full URI for AppSec to parse.
   */
  private static String buildFullPath(String path, Map<String, List<String>> queryParameters) {
    String query = buildQueryString(queryParameters);
    if (query == null || query.isEmpty()) {
      return path;
    }
    return path + "?" + query;
  }

  /**
   * Builds a URL-encoded query string (without the leading {@code ?}) from the parsed query
   * parameters, or null if there are none. Keys and values are percent-encoded so that special
   * characters (e.g. {@code &} inside a value) are not mistaken for query-string delimiters.
   */
  private static String buildQueryString(Map<String, List<String>> queryParameters) {
    if (queryParameters == null || queryParameters.isEmpty()) {
      return null;
    }

    StringBuilder query = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
      String key = entry.getKey();
      for (String value : entry.getValue()) {
        if (!first) {
          query.append('&');
        }
        first = false;
        try {
          query.append(URLEncoder.encode(key, "UTF-8"));
          if (value != null) {
            query.append('=').append(URLEncoder.encode(value, "UTF-8"));
          }
        } catch (java.io.UnsupportedEncodingException e) {
          // UTF-8 is always available; fall back to unencoded
          query.append(key);
          if (value != null) {
            query.append('=').append(value);
          }
        }
      }
    }

    return query.toString();
  }

  /**
   * Helper method to extract and merge headers with cookies array from event. API Gateway v2
   * provides a separate 'cookies' array that should be merged with headers.
   */
  private static Map<String, String> extractHeadersWithCookies(Map<String, Object> event) {
    Map<String, String> headers = extractHeaders(event.get("headers"));

    // API Gateway v2 provides a pre-parsed cookies array
    Object cookiesObj = event.get("cookies");
    if (cookiesObj instanceof List) {
      List<?> cookiesList = (List<?>) cookiesObj;
      if (!cookiesList.isEmpty()) {
        // Join cookies with "; " separator per RFC 6265
        String cookieValue =
            cookiesList.stream().map(String::valueOf).collect(Collectors.joining("; "));

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
   * Helper method to extract and parse body from event. Dispatches on the request's Content-Type
   * header (see {@link #parseBodyByContentType}).
   */
  private static Object extractBody(Map<String, Object> event, Map<String, String> headers) {
    Object bodyObj = event.get("body");
    if (bodyObj == null) {
      return null;
    }

    String bodyString = String.valueOf(bodyObj);

    // Check if body is base64 encoded (API Gateway feature)
    Object isBase64EncodedObj = event.get("isBase64Encoded");
    if (Boolean.TRUE.equals(isBase64EncodedObj) || "true".equals(isBase64EncodedObj)) {
      try {
        bodyString = new String(Base64.getDecoder().decode(bodyString), StandardCharsets.UTF_8);
      } catch (Exception e) {
        log.debug("Failed to decode base64 body", e);
        return null;
      }
    }

    String contentType = headers != null ? headers.get("content-type") : null;
    return parseBodyByContentType(bodyString, contentType);
  }

  /**
   * Parses a raw body string according to its Content-Type, dispatching strictly on the declared
   * type rather than guessing.
   *
   * <ul>
   *   <li>{@code application/x-www-form-urlencoded} → structured map.
   *   <li>A JSON content-type (contains {@code json} or {@code javascript}) → parsed as JSON, or
   *       dropped (null) if it fails to parse — a body that is malformed for its declared type is
   *       not analyzed.
   *   <li>A missing content-type → best-effort JSON, falling back to the raw string so the body
   *       stays scannable by string-based WAF rules.
   *   <li>Any other content-type (including {@code multipart/form-data}) → the raw string.
   *       Multipart bodies are not structurally parsed; the raw payload still stays
   *       scannable by string-based WAF rules.
   * </ul>
   */
  private static Object parseBodyByContentType(String bodyString, String contentType) {
    String contentTypeLower = contentType == null ? null : contentType.toLowerCase(Locale.ROOT);

    if (contentTypeLower != null
        && contentTypeLower.startsWith("application/x-www-form-urlencoded")) {
      return parseUrlEncodedBody(bodyString);
    }

    if (contentTypeLower != null
        && (contentTypeLower.contains("json") || contentTypeLower.contains("javascript"))) {
      // Explicit JSON content-type: parse as JSON. A body that fails to parse is malformed for its
      // declared type, so drop it (null) rather than forwarding a raw string
      return parseBodyAsJson(bodyString);
    }

    if (contentTypeLower == null) {
      // No declared content-type: best-effort JSON parse, falling back to the raw string so the
      // body stays scannable by string-based WAF rules.
      Object parsed = parseBodyAsJson(bodyString);
      return parsed != null ? parsed : bodyString;
    }

    // Any other (non-JSON) content-type: keep the raw string; do not guess JSON.
    return bodyString;
  }

  /** Helper method to parse body as JSON */
  private static Object parseBodyAsJson(String body) {
    if (body == null || body.isEmpty() || "null".equals(body)) {
      return null;
    }

    try {
      return OBJECT_ADAPTER.fromJson(body);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Parses an {@code application/x-www-form-urlencoded} body into a map of decoded keys to their
   * (possibly repeated) decoded values, e.g. {@code a=1&a=2&b=3} becomes {@code {a: [1, 2], b:
   * [3]}}.
   */
  private static Map<String, List<String>> parseUrlEncodedBody(String body) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    if (body == null || body.isEmpty()) {
      return result;
    }

    int start = 0;
    int len = body.length();
    while (start <= len) {
      int ampIdx = body.indexOf('&', start);
      String pair = ampIdx >= 0 ? body.substring(start, ampIdx) : body.substring(start);
      if (!pair.isEmpty()) {
        int eqIdx = pair.indexOf('=');
        String rawKey = eqIdx >= 0 ? pair.substring(0, eqIdx) : pair;
        String rawValue = eqIdx >= 0 ? pair.substring(eqIdx + 1) : "";
        String key = URIUtils.decode(rawKey, true);
        String value = URIUtils.decode(rawValue, true);
        result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
      }
      if (ampIdx < 0) {
        break;
      }
      start = ampIdx + 1;
    }
    return result;
  }

  /** Sets the current trigger type thread-local. Package-private for use in tests only. */
  static void setCurrentTriggerType(LambdaTriggerType type) {
    if (type == null) {
      CURRENT_TRIGGER_TYPE.remove();
    } else {
      CURRENT_TRIGGER_TYPE.set(type);
    }
  }

  /**
   * Temporary RequestContext implementation to hold AppSecRequestContext before a span is created.
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
    public void setClientIpAddressData(ClientIpAddressData clientIpAddressData) {
      // No-op for temporary context
    }

    @Override
    public ClientIpAddressData getClientIpAddressData() {
      return null;
    }

    @Override
    public void close() {
      // No-op for temporary context
    }
  }

  /** Enum representing different AWS Lambda trigger types */
  enum LambdaTriggerType {
    API_GATEWAY_V1_REST, // API Gateway REST API (v1)
    API_GATEWAY_V2_HTTP, // API Gateway HTTP API (v2)
    API_GATEWAY_V2_WEBSOCKET, // API Gateway WebSocket
    ALB, // Application Load Balancer
    ALB_MULTI_VALUE, // ALB with multi-value headers
    LAMBDA_URL, // Lambda Function URL
    UNKNOWN; // Unknown or unsupported trigger

    boolean isHttp() {
      return this != UNKNOWN;
    }
  }

  /** Object for Lambda event data needed for AppSec processing */
  static class LambdaEventData {
    final Map<String, String> headers;
    final String method;
    final String path;
    final String sourceIp;
    final Integer sourcePort;
    final LambdaTriggerType triggerType;
    final Map<String, String> pathParameters;
    final Map<String, List<String>> queryParameters;
    final Object body;
    final String route;

    static final LambdaEventData EMPTY =
        new LambdaEventData(
            Collections.emptyMap(),
            null,
            null,
            null,
            null,
            LambdaTriggerType.UNKNOWN,
            Collections.emptyMap(),
            Collections.emptyMap(),
            null,
            null);

    LambdaEventData(
        Map<String, String> headers,
        String method,
        String path,
        String sourceIp,
        Integer sourcePort,
        LambdaTriggerType triggerType,
        Map<String, String> pathParameters,
        Map<String, List<String>> queryParameters,
        Object body,
        String route) {
      this.headers = headers;
      this.method = method;
      this.path = path;
      this.sourceIp = sourceIp;
      this.sourcePort = sourcePort;
      this.triggerType = triggerType;
      this.pathParameters = pathParameters;
      this.queryParameters = queryParameters;
      this.body = body;
      this.route = route;
    }
  }

  /** Data extracted from a Lambda response for WAF analysis */
  static class LambdaResponseData {
    final int statusCode;
    final Map<String, String> headers;
    final Object body;

    LambdaResponseData(int statusCode, Map<String, String> headers, Object body) {
      this.statusCode = statusCode;
      this.headers = headers;
      this.body = body;
    }
  }

  /** URIDataAdapter implementation for Lambda events. */
  private static class LambdaURIDataAdapter extends URIDataAdapterBase {
    private final String path;
    private final String query;
    private final String scheme;
    private final int port;

    LambdaURIDataAdapter(String pathWithQuery, Map<String, String> headers) {
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

      String forwardedProto = firstForwardedValue(headers, "x-forwarded-proto");
      this.scheme =
          (forwardedProto != null && !forwardedProto.isEmpty()) ? forwardedProto : "https";

      String forwardedPort = firstForwardedValue(headers, "x-forwarded-port");
      int parsedPort = -1;
      if (forwardedPort != null && !forwardedPort.isEmpty()) {
        try {
          parsedPort = Integer.parseInt(forwardedPort.trim());
        } catch (NumberFormatException ignored) {
        }
      }
      this.port = parsedPort > 0 ? parsedPort : 443;
    }

    @Override
    public String scheme() {
      return scheme;
    }

    @Override
    public String host() {
      return null;
    }

    @Override
    public int port() {
      return port;
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
