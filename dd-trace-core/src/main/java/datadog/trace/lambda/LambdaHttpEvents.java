package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventData.get;
import static datadog.trace.lambda.LambdaEventData.headers;
import static datadog.trace.lambda.LambdaEventData.micros;
import static datadog.trace.lambda.LambdaEventData.multiHeaders;
import static datadog.trace.lambda.LambdaEventData.object;
import static datadog.trace.lambda.LambdaEventData.partition;
import static datadog.trace.lambda.LambdaEventData.service;
import static datadog.trace.lambda.LambdaEventData.string;
import static datadog.trace.lambda.LambdaEventData.tag;

import datadog.trace.lambda.LambdaEventParser.LambdaTriggerType;
import java.util.Map;
import java.util.regex.Pattern;

/** Bottlecap's API Gateway, Function URL and ALB event inference. */
final class LambdaHttpEvents {
  private static final Pattern IDENTIFIER =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{26}");
  private static final Pattern NUMBER = Pattern.compile("[0-9]+");

  private LambdaHttpEvents() {}

  static LambdaTrigger infer(
      Map<String, Object> event, String region, String functionName, Map<String, String> mapping) {
    Map<String, Object> rc = object(event.get("requestContext"));
    if (get(rc, "elb", "targetGroupArn") instanceof String) {
      LambdaTrigger trigger =
          new LambdaTrigger(
              "application-load-balancer", string(rc, "elb", "targetGroupArn"), false);
      trigger.carrier = headers(event.get("headers"));
      if (trigger.carrier.isEmpty()) {
        trigger.carrier = multiHeaders(event.get("multiValueHeaders"));
      }
      trigger.tags.put("http.method", string(event, "httpMethod"));
      trigger.httpType =
          event.containsKey("multiValueHeaders")
              ? LambdaTriggerType.ALB_MULTI_VALUE
              : LambdaTriggerType.ALB;
      return trigger;
    }
    if (string(rc, "domainName").contains("lambda-url")) {
      if (string(rc, "http", "method").isEmpty() || string(rc, "accountId").isEmpty()) {
        return null;
      }
      return http(event, rc, region, functionName, mapping, LambdaTriggerType.LAMBDA_URL);
    }
    if ("2.0".equals(event.get("version"))
        && event.containsKey("rawQueryString")
        && rc.containsKey("domainName")) {
      return string(rc, "http", "method").isEmpty()
          ? null
          : http(event, rc, region, functionName, mapping, LambdaTriggerType.API_GATEWAY_V2_HTTP);
    }
    if (event.containsKey("httpMethod")
        && event.containsKey("resource")
        && rc.containsKey("stage")) {
      return http(event, rc, region, functionName, mapping, LambdaTriggerType.API_GATEWAY_V1_REST);
    }
    if (rc.containsKey("messageDirection")) {
      return websocket(event, rc, region, mapping);
    }
    return null;
  }

  private static LambdaTrigger http(
      Map<String, Object> event,
      Map<String, Object> rc,
      String region,
      String functionName,
      Map<String, String> mapping,
      LambdaTriggerType type) {
    boolean rest = type == LambdaTriggerType.API_GATEWAY_V1_REST;
    boolean url = type == LambdaTriggerType.LAMBDA_URL;
    Map<String, Object> request = rest ? rc : object(rc.get("http"));
    Map<String, Object> identity = rest ? object(rc.get("identity")) : request;
    String method = string(request, rest ? "httpMethod" : "method");
    String path = string(request, "path");
    String domain = string(rc, "domainName");
    String apiId = string(rc, "apiId");
    String arn =
        url
            ? "arn:"
                + partition(region)
                + ":lambda:"
                + region
                + ":"
                + string(rc, "accountId")
                + ":url:"
                + functionName
            : apiArn(region, apiId, rest) + "/stages/" + string(rc, "stage");
    Map<String, String> carrier = headers(event.get("headers"));
    LambdaTrigger trigger =
        new LambdaTrigger(
            url ? "lambda-function-url" : "api-gateway",
            arn,
            "Event".equals(carrier.get("x-amz-invocation-type")));
    trigger.span(
        url ? "aws.lambda.url" : rest ? "aws.apigateway" : "aws.httpapi",
        service(mapping, apiId, url ? "lambda_url" : "lambda_api_gateway", domain),
        method + " " + (url ? path : parameterize(path)),
        micros(rc.get(rest ? "requestTimeEpoch" : "timeEpoch"), 1_000));
    trigger.type = url ? "http" : "web";
    trigger.httpType = type;
    trigger.carrier = carrier;
    trigger.meta.put("endpoint", path);
    trigger.meta.put("http.url", "https://" + domain + path);
    trigger.meta.put("http.method", method);
    trigger.meta.put("http.protocol", string(request, "protocol"));
    trigger.meta.put("http.source_ip", string(identity, "sourceIp"));
    trigger.meta.put("http.user_agent", string(identity, "userAgent"));
    trigger.meta.put("request_id", string(rc, "requestId"));
    trigger.tags.put("http.url", trigger.meta.get("http.url"));
    trigger.tags.put("http.url_details.path", path);
    trigger.tags.put("http.method", method);
    if (rest) {
      trigger.meta.put("http.route", string(rc, "resourcePath"));
      trigger.tags.put("http.route", string(rc, "resourcePath"));
    } else if (!url && !string(event, "routeKey").isEmpty()) {
      String[] route = string(event, "routeKey").split("\\s+");
      trigger.tags.put("http.route", route[route.length - 1]);
    }
    tag(trigger.tags, "http.referer", carrier.get("referer"));
    tag(
        trigger.tags,
        "http.user_agent",
        rest || url ? string(identity, "userAgent") : carrier.get("user-agent"));
    if (!url && !apiId.isEmpty()) {
      trigger.meta.put("dd_resource_key", apiArn(region, apiId, rest));
    }
    return trigger;
  }

  private static LambdaTrigger websocket(
      Map<String, Object> event,
      Map<String, Object> rc,
      String region,
      Map<String, String> mapping) {
    String domain = string(rc, "domainName");
    String route = string(rc, "routeKey");
    LambdaTrigger trigger =
        new LambdaTrigger(
            "api-gateway",
            apiArn(region, string(rc, "apiId"), false) + "/stages/" + string(rc, "stage"),
            true);
    trigger.span(
        "aws.apigateway",
        service(mapping, string(rc, "apiId"), "lambda_api_gateway", domain),
        route,
        micros(rc.get("requestTimeEpoch"), 1_000));
    trigger.carrier = headers(event.get("headers"));
    trigger.httpType = LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET;
    trigger.meta.put("endpoint", route);
    trigger.meta.put("resource_names", route);
    trigger.meta.put("http.url", "https://" + domain + route);
    trigger.meta.put("request_id", string(rc, "requestId"));
    trigger.meta.put("apiid", string(rc, "apiId"));
    trigger.meta.put("stage", string(rc, "stage"));
    trigger.meta.put("connection_id", string(rc, "connectionId"));
    trigger.meta.put("event_type", string(rc, "eventType"));
    trigger.meta.put("message_direction", string(rc, "messageDirection"));
    trigger.tags.put("http.url", trigger.meta.get("http.url"));
    trigger.tags.put("http.url_details.path", route);
    tag(trigger.tags, "http.referer", trigger.carrier.get("referer"));
    return trigger;
  }

  private static String apiArn(String region, String apiId, boolean rest) {
    return "arn:"
        + partition(region)
        + ":apigateway:"
        + region
        + "::/"
        + (rest ? "restapis" : "apis")
        + "/"
        + apiId;
  }

  static String parameterize(String path) {
    if (path.contains("{") && path.contains("}")) {
      return path;
    }
    String[] segments = path.split("/", -1);
    StringBuilder result = new StringBuilder();
    for (int i = 1; i < segments.length; i++) {
      String segment = segments[i];
      if (segment.isEmpty()) {
        continue;
      }
      if (NUMBER.matcher(segment).matches() || IDENTIFIER.matcher(segment).find()) {
        String parameter =
            i > 1 && !segments[i - 1].isEmpty() ? segments[i - 1].replaceAll("s+$", "") : "id";
        segment = "{" + parameter + ("id".equals(parameter) ? "" : "_id") + "}";
      }
      result.append('/').append(segment);
    }
    return result.toString();
  }
}
