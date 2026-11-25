package datadog.trace.lambda;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.tabletest.junit.TableTest;

/** Fixtures and expected behavior shared with Bottlecap's Rust port. */
class LambdaTriggerExtractorTest {
  static String fixture(String name) throws IOException {
    try (InputStream input =
        LambdaTriggerExtractorTest.class.getResourceAsStream("inference/" + name + ".json")) {
      assertNotNull(input, name);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        output.write(buffer, 0, count);
      }
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  static LambdaTrigger infer(Object event) {
    return new LambdaTriggerExtractor("us-east-1", "function", emptyMap()).extract(event);
  }

  @TableTest({
    "fixture                                | operation      | resource                        | async",
    "api_gateway_http_event                 | aws.httpapi    | GET /httpapi/get                | false",
    "api_gateway_http_event_parameterized   | aws.httpapi    | GET /user/{user_id}             | false",
    "api_gateway_rest_event                 | aws.apigateway | GET /my/path                    | false",
    "api_gateway_rest_event_parameterized   | aws.apigateway | GET /dev/user/{user_id}/id/{id} | false",
    "api_gateway_proxy_event                | aws.apigateway | POST /prod/path/to/resource     | false",
    "api_gateway_websocket_connect_event    | aws.apigateway | hello                           | true ",
    "api_gateway_websocket_disconnect_event | aws.apigateway | hello                           | true ",
    "api_gateway_websocket_message_event    | aws.apigateway | hello                           | true ",
    "lambda_function_url_event              | aws.lambda.url | GET /                           | false"
  })
  void inferredService(String fixture, String operation, String resource, boolean async)
      throws IOException {
    LambdaTrigger trigger = infer(fixture(fixture));
    assertEquals(operation, trigger.name);
    assertEquals(resource, trigger.resource);
    assertEquals(async, trigger.async);
    assertEquals(operation, trigger.meta.get("operation_name"));
    assertEquals("self", trigger.meta.get("_inferred_span.tag_source"));
    assertTrue(trigger.startMicros > 0);
    assertNotNull(trigger.tags.get("function_trigger.event_source"));
  }

  @TableTest({
    "fixture                                     ",
    "application_load_balancer                   ",
    "application_load_balancer_multivalue_headers"
  })
  void albOnlyExtractsContext(String fixture) throws IOException {
    LambdaTrigger trigger = infer(fixture(fixture));
    assertNull(trigger.name);
    assertFalse(trigger.carrier.isEmpty());
    assertEquals("application-load-balancer", trigger.tags.get("function_trigger.event_source"));
    assertTrue(
        trigger
            .tags
            .get("function_trigger.event_source_arn")
            .startsWith("arn:aws:elasticloadbalancing:"));
  }

  @TableTest({
    "region          | partition ",
    "us-east-1       | aws       ",
    "cn-north-1      | aws-cn    ",
    "us-gov-west-1   | aws-us-gov",
    "us-iso-east-1   | aws-iso   ",
    "us-isob-east-1  | aws-iso-b ",
    "eu-isoe-west-1  | aws-iso-e ",
    "us-isof-south-1 | aws-iso-f ",
    "eusc-de-east-1  | aws-eusc  "
  })
  void partitionsAndApiNamespaces(String region, String partition) throws IOException {
    LambdaTriggerExtractor extractor = new LambdaTriggerExtractor(region, "function", emptyMap());
    LambdaTrigger url = extractor.extract(fixture("lambda_function_url_event"));
    assertEquals(
        "arn:" + partition + ":lambda:" + region + ":601427279990:url:function",
        url.tags.get("function_trigger.event_source_arn"));
    LambdaTrigger http = extractor.extract(fixture("api_gateway_http_event"));
    String api = "arn:" + partition + ":apigateway:" + region + "::/apis/x02yirxc7a";
    assertEquals(api + "/stages/$default", http.tags.get("function_trigger.event_source_arn"));
    assertEquals(api, http.meta.get("dd_resource_key"));
    LambdaTrigger rest = extractor.extract(fixture("api_gateway_rest_event"));
    assertEquals(
        "arn:" + partition + ":apigateway:" + region + "::/restapis/id",
        rest.meta.get("dd_resource_key"));
  }
}
