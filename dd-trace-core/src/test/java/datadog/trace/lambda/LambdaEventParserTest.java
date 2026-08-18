package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventParser.buildFullPath;
import static datadog.trace.lambda.LambdaEventParser.findHeader;
import static datadog.trace.lambda.LambdaEventParser.parseEvent;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.lambda.LambdaEventParser.LambdaRequestData;
import datadog.trace.lambda.LambdaEventParser.LambdaTriggerType;
import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

/** Unit tests for the host and route extraction that feeds the HTTP span tags. */
class LambdaEventParserTest {

  // ============================================================================
  // API Gateway v1 (REST)
  // ============================================================================

  @Test
  void restApiPrefersDomainNameOverHostHeader() {
    LambdaRequestData data =
        parseEvent(
            "{\"resource\": \"/users/{id}\", \"path\": \"/users/42\", \"httpMethod\": \"GET\","
                + " \"headers\": {\"Host\": \"header.example.com\"},"
                + " \"requestContext\": {\"httpMethod\": \"GET\", \"domainName\":"
                + " \"context.example.com\"}}");

    assertEquals(LambdaTriggerType.API_GATEWAY_V1_REST, data.triggerType);
    assertEquals("context.example.com", data.host);
  }

  @Test
  void restApiFallsBackToCapitalisedHostHeader() {
    LambdaRequestData data =
        parseEvent(
            "{\"path\": \"/users/42\", \"httpMethod\": \"GET\", \"headers\": {\"Host\":"
                + " \"abc123.execute-api.eu-west-1.amazonaws.com\"}, \"requestContext\":"
                + " {\"httpMethod\": \"GET\"}}");

    assertEquals("abc123.execute-api.eu-west-1.amazonaws.com", data.host);
  }

  @Test
  void restApiRouteIsTheResource() {
    LambdaRequestData data =
        parseEvent(
            "{\"resource\": \"/users/{id}\", \"path\": \"/users/42\", \"httpMethod\": \"GET\","
                + " \"requestContext\": {\"httpMethod\": \"GET\"}}");

    assertEquals("/users/{id}", data.route);
  }

  @Test
  void restApiHasNoRouteWhenResourceIsMissing() {
    LambdaRequestData data =
        parseEvent(
            "{\"path\": \"/users/42\", \"httpMethod\": \"GET\", \"requestContext\":"
                + " {\"httpMethod\": \"GET\"}}");

    assertNull(data.route);
  }

  // ============================================================================
  // API Gateway v2 (HTTP) and Lambda Function URL
  // ============================================================================

  @Test
  void httpApiStripsTheMethodFromTheRouteKey() {
    LambdaRequestData data =
        parseEvent(
            "{\"headers\": {\"host\": \"api.example.com\"}, \"requestContext\": {\"domainName\":"
                + " \"api.example.com\", \"routeKey\": \"GET /users/{id}\", \"http\": {\"method\":"
                + " \"GET\", \"path\": \"/users/42\"}}}");

    assertEquals(LambdaTriggerType.API_GATEWAY_V2_HTTP, data.triggerType);
    assertEquals("api.example.com", data.host);
    assertEquals("/users/{id}", data.route);
  }

  @Test
  void httpApiDropsTheDefaultRouteKey() {
    LambdaRequestData data =
        parseEvent(
            "{\"requestContext\": {\"domainName\": \"api.example.com\", \"routeKey\":"
                + " \"$default\", \"http\": {\"method\": \"GET\", \"path\": \"/\"}}}");

    assertNull(data.route);
  }

  @Test
  void functionUrlHasNoRouteAndTakesHostFromDomainName() {
    LambdaRequestData data =
        parseEvent(
            "{\"requestContext\": {\"domainName\":"
                + " \"abc.lambda-url.eu-west-1.on.aws\", \"routeKey\": \"$default\", \"http\":"
                + " {\"method\": \"POST\", \"path\": \"/\"}}}");

    assertEquals(LambdaTriggerType.LAMBDA_URL, data.triggerType);
    assertEquals("abc.lambda-url.eu-west-1.on.aws", data.host);
    assertNull(data.route);
  }

  @Test
  void httpApiExposesTheRawRequestLine() {
    LambdaRequestData data =
        parseEvent(
            "{\"rawPath\": \"/users/42\", \"rawQueryString\": \"a=1&a=2\", \"requestContext\":"
                + " {\"domainName\": \"api.example.com\", \"http\": {\"method\": \"GET\","
                + " \"path\": \"/users/42\"}}}");

    assertEquals("/users/42?a=1&a=2", data.rawUri);
  }

  @Test
  void restApiExposesNoRawRequestLine() {
    // Only v2 payloads carry rawPath/rawQueryString; v1 has to be rebuilt from its parameter map
    LambdaRequestData data =
        parseEvent(
            "{\"path\": \"/users/42\", \"httpMethod\": \"GET\", \"requestContext\":"
                + " {\"httpMethod\": \"GET\"}}");

    assertNull(data.rawUri);
  }

  // ============================================================================
  // API Gateway v2 (WebSocket)
  // ============================================================================

  @Test
  void webSocketRouteIsTheRawRouteKey() {
    LambdaRequestData data =
        parseEvent(
            "{\"requestContext\": {\"connectionId\": \"c1\", \"eventType\": \"CONNECT\","
                + " \"routeKey\": \"$connect\", \"domainName\": \"ws.example.com\"}}");

    assertEquals(LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET, data.triggerType);
    assertEquals("ws.example.com", data.host);
    assertEquals("$connect", data.route);
  }

  // ============================================================================
  // ALB
  // ============================================================================

  @Test
  void albTakesHostFromHeaderAndHasNoRoute() {
    LambdaRequestData data =
        parseEvent(
            "{\"httpMethod\": \"GET\", \"path\": \"/alb\", \"headers\": {\"host\":"
                + " \"lb-123.eu-west-1.elb.amazonaws.com\"}, \"requestContext\": {\"elb\":"
                + " {\"targetGroupArn\": \"arn\"}}}");

    assertEquals(LambdaTriggerType.ALB, data.triggerType);
    assertEquals("lb-123.eu-west-1.elb.amazonaws.com", data.host);
    assertNull(data.route);
  }

  @Test
  void albStripsThePortFromTheHostHeader() {
    LambdaRequestData data =
        parseEvent(
            "{\"httpMethod\": \"GET\", \"path\": \"/alb\", \"headers\": {\"host\":"
                + " \"lb.example.com:8080\"}, \"requestContext\": {\"elb\": {\"targetGroupArn\":"
                + " \"arn\"}}}");

    // The port is carried separately, by x-forwarded-port
    assertEquals("lb.example.com", data.host);
  }

  @Test
  void albKeepsAnIpv6HostIntact() {
    LambdaRequestData data =
        parseEvent(
            "{\"httpMethod\": \"GET\", \"path\": \"/alb\", \"headers\": {\"host\": \"[::1]\"},"
                + " \"requestContext\": {\"elb\": {\"targetGroupArn\": \"arn\"}}}");

    assertEquals("[::1]", data.host);
  }

  @Test
  void albMultiValueTakesHostFromMultiValueHeaders() {
    LambdaRequestData data =
        parseEvent(
            "{\"httpMethod\": \"GET\", \"path\": \"/alb\", \"multiValueHeaders\": {\"host\":"
                + " [\"lb-123.eu-west-1.elb.amazonaws.com\"]}, \"requestContext\": {\"elb\":"
                + " {\"targetGroupArn\": \"arn\"}}}");

    assertEquals(LambdaTriggerType.ALB_MULTI_VALUE, data.triggerType);
    assertEquals("lb-123.eu-west-1.elb.amazonaws.com", data.host);
  }

  @Test
  void albMultiValueFallsBackToSingleValueHeaders() {
    LambdaRequestData data =
        parseEvent(
            "{\"httpMethod\": \"GET\", \"path\": \"/alb\", \"multiValueHeaders\":"
                + " \"not-a-map\", \"headers\": {\"host\": \"fallback.example.com\"},"
                + " \"requestContext\": {\"elb\": {\"targetGroupArn\": \"arn\"}}}");

    assertEquals(LambdaTriggerType.ALB_MULTI_VALUE, data.triggerType);
    assertEquals("fallback.example.com", data.host);
  }

  @Test
  void albMultiValueFallsBackToSingleValueQueryParameters() {
    LambdaRequestData data =
        parseEvent(
            "{\"httpMethod\": \"GET\", \"path\": \"/alb\", \"multiValueHeaders\": {\"host\":"
                + " [\"lb.example.com\"]}, \"queryStringParameters\": {\"q\": \"hello\"},"
                + " \"requestContext\": {\"elb\": {\"targetGroupArn\": \"arn\"}}}");

    assertEquals(LambdaTriggerType.ALB_MULTI_VALUE, data.triggerType);
    assertEquals(singletonList("hello"), data.queryParameters.get("q"));
  }

  @Test
  void queryParametersKeepEventOrder() {
    LambdaRequestData data =
        parseEvent(
            "{\"resource\": \"/items\", \"path\": \"/items\", \"queryStringParameters\": {\"z\":"
                + " \"1\", \"a\": \"2\", \"m\": \"3\"}, \"requestContext\": {\"httpMethod\":"
                + " \"GET\", \"domainName\": \"api.example.com\"}}");

    // Order drives the rebuilt query string, so it must follow the event, not a hash order
    assertEquals(asList("z", "a", "m"), new ArrayList<>(data.queryParameters.keySet()));
    assertEquals("/items?z=1&a=2&m=3", buildFullPath(data.path, data.queryParameters));
  }

  // ============================================================================
  // Non-HTTP and malformed payloads
  // ============================================================================

  @Test
  void nonHttpEventHasNoHostOrRoute() {
    LambdaRequestData data =
        parseEvent("{\"Records\": [{\"eventSource\": \"aws:sqs\", \"body\": \"hello\"}]}");

    assertEquals(LambdaTriggerType.UNKNOWN, data.triggerType);
    assertNull(data.host);
    assertNull(data.route);
  }

  @Test
  void malformedPayloadReturnsEmpty() {
    assertSame(LambdaRequestData.EMPTY, parseEvent("{not json"));
  }

  // ============================================================================
  // Header casing
  // ============================================================================

  @Test
  void headerKeysAreLowercasedAtExtraction() {
    LambdaRequestData data =
        parseEvent(
            "{\"resource\": \"/users/{id}\", \"path\": \"/users/42\", \"httpMethod\": \"GET\","
                + " \"headers\": {\"Host\": \"api.example.com\", \"User-Agent\": \"curl/8.1\"},"
                + " \"requestContext\": {\"httpMethod\": \"GET\"}}");

    // findHeader is an exact lookup, so a match proves the keys were lowercased on the way in
    assertEquals("api.example.com", findHeader(data.headers, "host"));
    assertEquals("curl/8.1", findHeader(data.headers, "user-agent"));
    assertEquals(2, data.headers.size());
  }

  @Test
  void albMultiValueHeaderKeysAreLowercasedAtExtraction() {
    LambdaRequestData data =
        parseEvent(
            "{\"httpMethod\": \"GET\", \"path\": \"/alb\", \"multiValueHeaders\": {\"Host\":"
                + " [\"lb.example.com\"], \"User-Agent\": [\"curl/8.1\"]}, \"requestContext\":"
                + " {\"elb\": {\"targetGroupArn\": \"arn\"}}}");

    assertEquals("lb.example.com", findHeader(data.headers, "host"));
    assertEquals("curl/8.1", findHeader(data.headers, "user-agent"));
  }

  @Test
  void capitalisedCookieHeaderIsMergedWithTheV2CookiesArray() {
    // The merge in extractHeadersWithCookies looks up "cookie", which only matches once keys are
    // lowercased at extraction — an API Gateway v1 style "Cookie" used to be left as a second entry
    LambdaRequestData data =
        parseEvent(
            "{\"headers\": {\"Cookie\": \"a=1\"}, \"cookies\": [\"b=2\"], \"requestContext\":"
                + " {\"domainName\": \"api.example.com\", \"http\": {\"method\": \"GET\","
                + " \"path\": \"/\"}}}");

    assertEquals("a=1; b=2", findHeader(data.headers, "cookie"));
    assertEquals(1, data.headers.size());
  }

  @Test
  void findHeaderIsNullSafe() {
    assertNull(findHeader(null, "user-agent"));
    assertNull(findHeader(new HashMap<>(), "user-agent"));
  }
}
