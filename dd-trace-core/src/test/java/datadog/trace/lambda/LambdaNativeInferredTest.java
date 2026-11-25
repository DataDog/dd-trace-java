package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventData.json;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@WithConfig(key = "service", value = "function-service")
class LambdaNativeInferredTest extends DDCoreJavaSpecification {
  private static final String SQS =
      "{\"Records\":[{\"eventSource\":\"aws:sqs\",\"eventSourceARN\":\"arn:aws:sqs:us-east-1:123:queue\",\"attributes\":{\"SentTimestamp\":\"1000\"}}]}";

  @TableTest({
    "representation | remove | mapping                           | service         ",
    "true           | false  | ''                                | queue           ",
    "false          | false  | ''                                | sqs             ",
    "true           | true   | ''                                | function-service",
    "false          | true   | lambda_sqs:custom                 | custom          ",
    "true           | true   | queue:specific,lambda_sqs:generic | specific        "
  })
  void serviceControlsKeepMappingsAndInvocationService(
      boolean representation, boolean remove, String mapping, String service) {
    injectSysConfig("trace.aws.service.representation.enabled", Boolean.toString(representation));
    injectSysConfig("trace.remove.integration-service-names.enabled", Boolean.toString(remove));
    injectSysConfig("service.mapping", mapping);
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      LambdaInvocation invocation = start(tracer, SQS);
      invocation.onExit(null, null);
      invocation.finish();
      Map<String, DDSpan> spans = spans(writer);
      assertEquals(service, spans.get("aws.sqs").getServiceName());
      assertEquals("function-service", spans.get("aws.lambda").getServiceName());
      assertEquals(spans.get("aws.sqs").getSpanId(), spans.get("aws.lambda").getParentId());
      assertEquals(
          invocation.span().getStartTime(),
          spans.get("aws.sqs").getStartTime() + spans.get("aws.sqs").getDurationNano());
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = "trace.span.attribute.schema", value = "v1")
  void httpSpanCoversInvocationAndCarriesErrorsAndAppSec() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      String event =
          "{\"version\":\"2.0\",\"rawQueryString\":\"\",\"requestContext\":{\"domainName\":\"api.example.com\",\"apiId\":\"api\",\"stage\":\"$default\",\"timeEpoch\":1000,\"http\":{\"method\":\"GET\",\"path\":\"/items/123\"}}}";
      LambdaInvocation invocation = start(tracer, event);
      invocation.span().setTag("_dd.appsec.json", "{\"triggers\":[]}");
      invocation.span().setTag("_dd.appsec.enabled", 1);
      invocation.span().setMetaStruct("appsec", singletonMap("test", true));
      invocation.onExit("{\"statusCode\":503}", null);
      invocation.finish();
      Map<String, DDSpan> spans = spans(writer);
      DDSpan http = spans.get("aws.httpapi");
      DDSpan lambda = spans.get("aws.lambda");
      assertEquals("api.example.com", http.getServiceName());
      assertEquals("function-service", lambda.getServiceName());
      assertTrue(http.isError());
      assertEquals("503", http.getTag("http.status_code").toString());
      assertEquals(lambda.getTag("_dd.appsec.json"), http.getTag("_dd.appsec.json"));
      assertEquals(lambda.getMetaStruct().get("appsec"), http.getMetaStruct().get("appsec"));
      assertTrue(
          http.getStartTime() + http.getDurationNano()
              >= lambda.getStartTime() + lambda.getDurationNano() - 1_000);
      assertEquals(
          "arn:aws:apigateway:us-east-1::/apis/api/stages/$default",
          lambda.getTag("function_trigger.event_source_arn"));
    } finally {
      tracer.close();
    }
  }

  @Test
  void wrappedEventSpansKeepTheirOrderAndCompleteOnce() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      Map<String, Object> event = json(SQS);
      Map<String, Object> record = LambdaEventData.first(event.get("Records"));
      record.put(
          "body",
          "{\"TopicArn\":\"arn:aws:sns:us-east-1:123:topic\",\"Timestamp\":\"1970-01-01T00:00:00.500Z\"}");
      LambdaInvocation invocation = start(tracer, event);
      invocation.onExit(null, new IllegalStateException("failed"));
      invocation.finish();
      invocation.finish();
      Map<String, DDSpan> spans = spans(writer);
      assertEquals(3, spans.size());
      assertEquals(spans.get("aws.sns").getSpanId(), spans.get("aws.sqs").getParentId());
      assertEquals(spans.get("aws.sqs").getSpanId(), spans.get("aws.lambda").getParentId());
      assertEquals(
          spans.get("aws.sqs").getStartTime(),
          spans.get("aws.sns").getStartTime() + spans.get("aws.sns").getDurationNano());
      assertEquals("failed", spans.get("aws.sns").getTag("error.message"));
      assertTrue(spans.get("aws.sqs").isError());
    } finally {
      tracer.close();
    }
  }

  private LambdaInvocation start(CoreTracer tracer, Object event) {

    return LambdaInvocation.start(
        tracer,
        Config.get(),
        event,
        "request",
        "function",
        "1",
        "arn:aws:lambda:us-east-1:123:function:function");
  }

  private Map<String, DDSpan> spans(ListWriter writer) {
    assertEquals(1, writer.size());
    Map<String, DDSpan> spans = new HashMap<>();
    for (DDSpan span : writer.get(0)) {
      spans.put(span.getOperationName().toString(), span);
    }
    return spans;
  }
}
