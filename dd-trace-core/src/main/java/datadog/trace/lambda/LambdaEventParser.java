package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses AWS Lambda invocation payloads: detects the trigger type and extracts the HTTP request and
 * response data the AppSec gateway callbacks need. Contains no AppSec logic.
 */
final class LambdaEventParser {

  private static final Logger log = LoggerFactory.getLogger(LambdaEventParser.class);

  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<Map> MAP_ADAPTER = MOSHI.adapter(Map.class);
  private static final JsonAdapter<Object> OBJECT_ADAPTER = MOSHI.adapter(Object.class);

  static final int MAX_EVENT_SIZE = Config.get().getAppSecBodyParsingSizeLimit();

  private LambdaEventParser() {}

  /**
   * Parses a Lambda event payload without consuming it.
   *
   * @param inputStream the raw event payload
   * @return the extracted event data, or {@link LambdaRequestData#EMPTY} if the payload is empty,
   *     too large or unparseable
   */
  static LambdaRequestData parseEvent(ByteArrayInputStream inputStream) throws IOException {
    inputStream.mark(0);
    try {
      int availableBytes = inputStream.available();

      if (availableBytes <= 0 || availableBytes > MAX_EVENT_SIZE) {
        log.debug(
            "Event size {} exceeds limit {} or is invalid, skipping AppSec processing",
            availableBytes,
            MAX_EVENT_SIZE);
        return LambdaRequestData.EMPTY;
      }

      byte[] bytes = new byte[availableBytes];
      int read = inputStream.read(bytes);
      if (read <= 0) {
        return LambdaRequestData.EMPTY;
      }
      return parseEvent(new String(bytes, 0, read, StandardCharsets.UTF_8));
    } finally {
      inputStream.reset();
    }
  }

  static LambdaRequestData parseEvent(String json) {
    try {
      // Parse JSON into a Map
      Map<String, Object> event = MAP_ADAPTER.fromJson(json);
      log.debug("Event JSON parsed successfully");

      if (event == null) {
        return LambdaRequestData.EMPTY;
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
      return LambdaRequestData.EMPTY;
    }
  }

  /**
   * Parses a Lambda handler response.
   *
   * @param json the raw response payload
   * @return the extracted response data, or {@code null} if the payload is not a JSON object
   */
  static LambdaResponseData parseResponse(String json) {
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
      Map<String, String> headers = new HashMap<>();
      Map<String, String> rawHeaders = extractStringMap(response.get("headers"));
      for (Map.Entry<String, String> entry : rawHeaders.entrySet()) {
        headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
      }

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

          // If JSON content-type or unknown, attempt JSON parsing
          // Normalise casing: media type tokens are case-insensitive per RFC 7231
          String contentTypeLower =
              contentType == null ? null : contentType.toLowerCase(Locale.ROOT);
          if (contentTypeLower == null
              || contentTypeLower.contains("json")
              || contentTypeLower.contains("javascript")) {
            Object parsed = parseBodyAsJson(bodyString);
            body = parsed != null ? parsed : bodyString;
          } else {
            body = bodyString;
          }
        }
      }

      return new LambdaResponseData(statusCode, headers, body);
    } catch (Exception e) {
      log.debug("Failed to parse response data from JSON", e);
      return null;
    }
  }

  /**
   * Parses an arbitrary JSON value, propagating parse failures so the caller can distinguish a
   * malformed payload from a JSON {@code null}.
   */
  static Object parseJsonValue(String json) throws IOException {
    return OBJECT_ADAPTER.fromJson(json);
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

  /** Extracts data from API Gateway v1 (REST API) event */
  private static LambdaRequestData extractApiGatewayV1Data(Map<String, Object> event) {
    Map<String, String> headers = extractHeaders(event.get("headers"));
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Map<String, List<String>> queryParameters =
        extractQueryParameters(event.get("queryStringParameters"));
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

    return new LambdaRequestData(
        headers,
        method,
        path,
        sourceIp,
        null,
        LambdaTriggerType.API_GATEWAY_V1_REST,
        pathParameters,
        queryParameters,
        body);
  }

  /** Extracts data from API Gateway v2 (HTTP API) or Lambda URL event */
  private static LambdaRequestData extractApiGatewayV2HttpData(
      Map<String, Object> event, LambdaTriggerType triggerType) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Map<String, List<String>> queryParameters =
        extractQueryParameters(event.get("queryStringParameters"));
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

    return new LambdaRequestData(
        headers,
        method,
        path,
        sourceIp,
        sourcePort,
        triggerType,
        pathParameters,
        queryParameters,
        body);
  }

  /** Extracts data from API Gateway v2 WebSocket event */
  private static LambdaRequestData extractApiGatewayV2WebSocketData(Map<String, Object> event) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Map<String, List<String>> queryParameters =
        extractQueryParameters(event.get("queryStringParameters"));
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

    return new LambdaRequestData(
        headers,
        method,
        path,
        sourceIp,
        null,
        LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET,
        pathParameters,
        queryParameters,
        body);
  }

  /** Extracts data from ALB event (with or without multi-value headers) */
  private static LambdaRequestData extractAlbData(
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
            String key = String.valueOf(entry.getKey());
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

    Object body = extractBody(event);

    String method = (String) event.get("httpMethod");
    String path = (String) event.get("path");
    String xff = headers.get("x-forwarded-for");
    String sourceIp = null;
    if (xff != null) {
      int commaIdx = xff.indexOf(',');
      sourceIp = (commaIdx >= 0 ? xff.substring(0, commaIdx) : xff).trim();
    }

    return new LambdaRequestData(
        headers, method, path, sourceIp, null, triggerType, pathParameters, queryParameters, body);
  }

  /** Generic data extraction for unknown trigger types (fallback) */
  private static LambdaRequestData extractGenericData(Map<String, Object> event) {
    Map<String, String> headers = extractHeadersWithCookies(event);
    Map<String, String> pathParameters = extractPathParameters(event.get("pathParameters"));
    Map<String, List<String>> queryParameters =
        extractQueryParameters(event.get("queryStringParameters"));
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

    return new LambdaRequestData(
        headers,
        method,
        path,
        sourceIp,
        null,
        LambdaTriggerType.UNKNOWN,
        pathParameters,
        queryParameters,
        body);
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

  /** Helper method to extract headers from event */
  private static Map<String, String> extractHeaders(Object headersObj) {
    Map<String, String> headers = extractStringMap(headersObj);
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
  static String buildFullPath(String path, Map<String, List<String>> queryParameters) {
    if (queryParameters == null || queryParameters.isEmpty()) {
      return path;
    }

    StringBuilder fullPath = new StringBuilder(path);
    fullPath.append('?');

    boolean first = true;
    for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
      String key = entry.getKey();
      for (String value : entry.getValue()) {
        if (!first) {
          fullPath.append('&');
        }
        first = false;
        try {
          // URL-encode key and value so that special characters (e.g. '&' inside a value) are not
          // mistaken for query string delimiters when AppSec parses the raw query string.
          fullPath.append(URLEncoder.encode(key, "UTF-8"));
          if (value != null) {
            fullPath.append('=').append(URLEncoder.encode(value, "UTF-8"));
          }
        } catch (java.io.UnsupportedEncodingException e) {
          // UTF-8 is always available; fall back to unencoded
          fullPath.append(key);
          if (value != null) {
            fullPath.append('=').append(value);
          }
        }
      }
    }

    return fullPath.toString();
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

  /** Helper method to extract and parse body from event */
  private static Object extractBody(Map<String, Object> event) {
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

  /** Object for Lambda request data needed for AppSec processing */
  static class LambdaRequestData {
    final Map<String, String> headers;
    final String method;
    final String path;
    final String sourceIp;
    final Integer sourcePort;
    final LambdaTriggerType triggerType;
    final Map<String, String> pathParameters;
    final Map<String, List<String>> queryParameters;
    final Object body;

    static final LambdaRequestData EMPTY =
        new LambdaRequestData(
            Collections.emptyMap(),
            null,
            null,
            null,
            null,
            LambdaTriggerType.UNKNOWN,
            Collections.emptyMap(),
            Collections.emptyMap(),
            null);

    LambdaRequestData(
        Map<String, String> headers,
        String method,
        String path,
        String sourceIp,
        Integer sourcePort,
        LambdaTriggerType triggerType,
        Map<String, String> pathParameters,
        Map<String, List<String>> queryParameters,
        Object body) {
      this.headers = headers;
      this.method = method;
      this.path = path;
      this.sourceIp = sourceIp;
      this.sourcePort = sourcePort;
      this.triggerType = triggerType;
      this.pathParameters = pathParameters;
      this.queryParameters = queryParameters;
      this.body = body;
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
}
