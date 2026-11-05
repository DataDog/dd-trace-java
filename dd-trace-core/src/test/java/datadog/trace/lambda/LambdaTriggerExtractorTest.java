package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventData.first;
import static datadog.trace.lambda.LambdaEventData.get;
import static datadog.trace.lambda.LambdaEventData.json;
import static datadog.trace.lambda.LambdaEventData.object;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
    "fixture                                | operation       | resource                            | async",
    "api_gateway_http_event                 | aws.httpapi     | GET /httpapi/get                    | false",
    "api_gateway_http_event_parameterized   | aws.httpapi     | GET /user/{user_id}                 | false",
    "api_gateway_rest_event                 | aws.apigateway  | GET /my/path                        | false",
    "api_gateway_rest_event_parameterized   | aws.apigateway  | GET /dev/user/{user_id}/id/{id}     | false",
    "api_gateway_proxy_event                | aws.apigateway  | POST /prod/path/to/resource         | false",
    "api_gateway_websocket_connect_event    | aws.apigateway  | hello                               | true ",
    "api_gateway_websocket_disconnect_event | aws.apigateway  | hello                               | true ",
    "api_gateway_websocket_message_event    | aws.apigateway  | hello                               | true ",
    "lambda_function_url_event              | aws.lambda.url  | GET /                               | false",
    "sqs_event                              | aws.sqs         | MyQueue                             | true ",
    "sqs_event_binary                       | aws.sqs         | MyQueue                             | true ",
    "sqs_event_binary_custom_label          | aws.sqs         | MyQueue                             | true ",
    "sqs_binary_event                       | aws.sqs         | java-dsm-demo-sns-demo-dev-dev      | true ",
    "sns_event                              | aws.sns         | serverlessTracingTopicPy            | true ",
    "sns_event_binary                       | aws.sns         | serverlessTracingTopicPy            | true ",
    "sns_sqs_event                          | aws.sqs         | aj-js-library-test-dev-demo-queue   | true ",
    "sns_sqs_binary_event                   | aws.sqs         | aj-js-library-test-dev-demo-queue   | true ",
    "eventbridge_event                      | aws.eventbridge | testBus                             | true ",
    "eventbridge_no_resource_name_event     | aws.eventbridge | my.event                            | true ",
    "eventbridge_no_timestamp_event         | aws.eventbridge | testBus                             | true ",
    "eventbridge_sns_event                  | aws.sns         | test-notifier                       | true ",
    "eventbridge_sqs_event                  | aws.sqs         | lambda-eb-sqs-lambda-dev-demo-queue | true ",
    "eventbridge_sqs_java_header_event      | aws.sqs         | lambda-eb-sqs-lambda-dev-demo-queue | true ",
    "s3_event                               | aws.s3          | example-bucket                      | true ",
    "dynamodb_event                         | aws.dynamodb    | INSERT ExampleTableWithStream       | true ",
    "kinesis_event                          | aws.kinesis     | kinesisStream                       | true ",
    "msk_event                              | aws.msk         | topic1                              | true ",
    "msk_event_with_headers                 | aws.msk         | demo-topic                          | true "
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
    "fixture                           | outer           | source     ",
    "sns_sqs_event                     | aws.sns         | sns        ",
    "sns_sqs_binary_event              | aws.sns         | sns        ",
    "eventbridge_sns_event             | aws.eventbridge | eventbridge",
    "eventbridge_sqs_event             | aws.eventbridge | eventbridge",
    "eventbridge_sqs_java_header_event | aws.eventbridge | eventbridge"
  })
  void wrappedCarriers(String fixture, String outer, String source) throws IOException {
    LambdaTrigger trigger = infer(fixture(fixture));
    assertEquals(outer, trigger.wrapped.name);
    assertEquals(source, trigger.meta.get("function_trigger.event_source"));
    assertFalse(trigger.meta.containsKey("function_trigger.event_source_arn"));
    assertNotNull(trigger.carrier.get("x-datadog-trace-id"));
    assertNull(trigger.wrapped.wrapped);
  }

  @TableTest({
    "fixture                       | traceId            ",
    "sqs_event                     | 2684756524522091840",
    "sqs_event_binary              | 1111111111111111111",
    "sqs_event_binary_custom_label | 3333333333333333333",
    "kinesis_event                 | 4948377316357291421",
    "eventbridge_event             | 5827606813695714842",
    "eventbridge_sns_event         | 12345              ",
    "msk_event_with_headers        | 1497116011738644768"
  })
  void decodesCarriers(String fixture, String traceId) throws IOException {
    assertEquals(traceId, infer(fixture(fixture)).carrier.get("x-datadog-trace-id"));
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

  @TableTest({
    "fixture                                | traceId                          | parentId           ",
    "step_function_event                    | 1914fe7789eb32be4fb6f07e011a6faf | 2902498116043018663",
    "step_function_legacy_event             | 1914fe7789eb32be4fb6f07e011a6faf | 2902498116043018663",
    "step_function_nested_event             | 579d19b3ee216ee9125980457978ecfe | 8947638978974359093",
    "step_function_nested_legacy_event      | 579d19b3ee216ee9125980457978ecfe | 8947638978974359093",
    "step_function_lambda_root_event        | 672a7cb10000000050cb33b3c06ae95c | 8947638978974359093",
    "step_function_lambda_root_legacy_event | 672a7cb10000000050cb33b3c06ae95c | 8947638978974359093"
  })
  void stepFunctions(String fixture, String traceId, long parentId) throws IOException {
    LambdaTrigger trigger = infer(fixture(fixture));
    assertNull(trigger.name);
    assertEquals(traceId, trigger.generatedContext.getTraceId().toHexString());
    assertEquals(parentId, trigger.generatedContext.getSpanId());
    assertEquals(PrioritySampling.SAMPLER_KEEP, trigger.generatedContext.getSamplingPriority());
    assertEquals(
        "states",
        ((datadog.trace.bootstrap.instrumentation.api.TagContext) trigger.generatedContext)
            .getOrigin());
  }

  @TableTest({
    "prefix   | sampled | priority",
    "abcdef12 | 0       | -128    ",
    "abcdef12 | 1       | 1       ",
    "00000000 | 0       | 0       ",
    "00000000 | 1       | 1       ",
    "00000000 | ''      | -128    "
  })
  void xraySampling(String prefix, String sampled, int priority) {
    AgentSpanContext context =
        LambdaGeneratedContext.xray(
            "Root=1-67891233-"
                + prefix
                + "ffffffffffffffff;Parent=fffffffffffffffe;Sampled="
                + sampled);
    assertNotNull(context);
    assertEquals("18446744073709551615", context.getTraceId().toString());
    assertEquals(-2L, context.getSpanId());
    assertEquals(priority, context.getSamplingPriority());
    assertNull(LambdaGeneratedContext.xray("Root=1-invalid;Parent=0"));
  }

  @Test
  void serviceMappingAndRecordMetadata() throws IOException {
    Map<String, String> mapping = new HashMap<>();
    mapping.put("lambda_sqs", "queues");
    LambdaTriggerExtractor extractor = new LambdaTriggerExtractor("us-east-1", "function", mapping);
    assertEquals("queues", extractor.extract(fixture("sqs_event")).service);
    mapping.put("MyQueue", "specific");
    assertEquals("specific", extractor.extract(fixture("sqs_event")).service);
    LambdaTrigger s3 = infer(fixture("s3_event"));
    assertEquals("1024", s3.meta.get("object_size"));
    assertEquals("arn:aws:s3:::example-bucket", s3.tags.get("function_trigger.event_source_arn"));
    Map<String, Object> deletion = json(fixture("s3_event"));
    Map<String, Object> deletedObject = object(get(first(deletion.get("Records")), "s3", "object"));
    deletedObject.remove("size");
    deletedObject.remove("eTag");
    LambdaTrigger deleted = infer(deletion);
    assertEquals("aws.s3", deleted.name);
    assertFalse(deleted.meta.containsKey("object_size"));
    assertFalse(deleted.meta.containsKey("object_etag"));
    assertEquals(1643638425163000L, infer(fixture("kinesis_event")).startMicros);
  }

  @Test
  void messageAttributeTakesPrecedenceOverWrappedCarrier() throws IOException {
    Map<String, Object> event = json(fixture("eventbridge_sqs_event"));
    Map<String, Object> record = first(event.get("Records"));
    record.put(
        "messageAttributes",
        json(
            "{\"_datadog\":{\"dataType\":\"String.custom\",\"stringValue\":\"{\\\"x-datadog-trace-id\\\":\\\"42\\\"}\"}}"));
    assertEquals("42", infer(event).carrier.get("x-datadog-trace-id"));
  }

  @Test
  void streamIsUnchangedAndMalformedEventsAreHarmless() throws IOException {
    byte[] bytes = fixture("sqs_event").getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream input = new ByteArrayInputStream(bytes);
    assertEquals("aws.sqs", infer(input).name);
    assertEquals(bytes.length, input.available());
    byte[] actual = new byte[bytes.length];
    assertEquals(bytes.length, input.read(actual));
    assertTrue(Arrays.equals(bytes, actual));
    for (String malformed :
        new String[] {
          "",
          "null",
          "[]",
          "{",
          "{\"Records\":null}",
          "{\"requestContext\":42}",
          "{\"records\":false}"
        }) {
      LambdaTrigger result = infer(malformed);
      assertNull(result.name);
      assertTrue(result.carrier.isEmpty());
    }
  }

  @Test
  void mskUsesRecordWithPropagationHeadersInsteadOfFirst() {
    Map<String, Object> event =
        json(
            "{\"eventSourceArn\":\"arn:aws:kafka:us-east-1:123:cluster/my-cluster/id\",\"eventSource\":\"aws:kafka\","
                + "\"records\":{\"topic-0\":[{\"topic\":\"first\",\"timestamp\":1000,\"headers\":[]},"
                + "{\"topic\":\"second\",\"timestamp\":2000,\"headers\":[{\"x-datadog-trace-id\":[52,50]}]}]}}");
    LambdaTrigger trigger = infer(event);
    assertEquals("second", trigger.resource);
    assertEquals("my-cluster", trigger.service);
    assertEquals(2000000, trigger.startMicros);
    assertEquals("42", trigger.carrier.get("x-datadog-trace-id"));
  }

  @Test
  void stepFunctionsLeadingZeroHighBitsAndRetries() throws IOException {
    Map<String, Object> event = json(fixture("step_function_event"));
    object(event.get("Execution"))
        .put("Id", "arn:aws:states:us-east-1:425362996713:execution:agocsTestSF:regression-3");
    LambdaTrigger initial = infer(event);
    assertEquals(
        "0a2cd4ab2e2818c46eed62ec038b1422", initial.generatedContext.getTraceId().toHexString());
    object(event.get("State")).put("RetryCount", 1);
    LambdaTrigger retried = infer(event);
    assertEquals(initial.generatedContext.getTraceId(), retried.generatedContext.getTraceId());
    assertFalse(initial.generatedContext.getSpanId() == retried.generatedContext.getSpanId());
  }
}
