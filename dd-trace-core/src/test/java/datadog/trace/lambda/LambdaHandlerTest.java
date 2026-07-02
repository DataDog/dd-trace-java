package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@SuppressWarnings({"unchecked", "rawtypes"})
public class LambdaHandlerTest extends DDCoreJavaSpecification {

  static class TestObject {
    public String field1;
    public boolean field2;

    TestObject() {
      this.field1 = "toto";
      this.field2 = true;
    }

    @Override
    public String toString() {
      return field1 + " / " + field2 + "}";
    }
  }

  @Test
  void testStartInvocationSuccess() {
    CoreTracer ct = tracerBuilder().build();

    JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            "/lambda/start-invocation",
                            api ->
                                api.getResponse()
                                    .status(200)
                                    .addHeader("x-datadog-trace-id", "1234")
                                    .addHeader("x-datadog-sampling-priority", "2")
                                    .send())));
    LambdaHandler.setExtensionBaseUrl(server.getAddress().toString());

    AgentSpanContext objTest =
        LambdaHandler.notifyStartInvocation(new TestObject(), "lambda-request-123");

    assertEquals("1234", objTest.getTraceId().toString());
    assertEquals(2, objTest.getSamplingPriority());
    assertEquals(
        "lambda-request-123", server.getLastRequest().getHeader("lambda-runtime-aws-request-id"));

    server.close();
    ct.close();
  }

  @Test
  void testStartInvocationWith128BitTraceId() {
    CoreTracer ct = tracerBuilder().build();

    JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            "/lambda/start-invocation",
                            api ->
                                api.getResponse()
                                    .status(200)
                                    .addHeader("x-datadog-trace-id", "5744042798732701615")
                                    .addHeader("x-datadog-sampling-priority", "2")
                                    .addHeader("x-datadog-tags", "_dd.p.tid=1914fe7789eb32be")
                                    .send())));
    LambdaHandler.setExtensionBaseUrl(server.getAddress().toString());

    AgentSpanContext objTest =
        LambdaHandler.notifyStartInvocation(new TestObject(), "lambda-request-123");

    assertEquals("1914fe7789eb32be4fb6f07e011a6faf", objTest.getTraceId().toHexString());
    assertEquals(2, objTest.getSamplingPriority());
    assertEquals(
        "lambda-request-123", server.getLastRequest().getHeader("lambda-runtime-aws-request-id"));

    server.close();
    ct.close();
  }

  @Test
  void testStartInvocationFailure() {
    CoreTracer ct = tracerBuilder().build();

    JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            "/lambda/start-invocation",
                            api -> api.getResponse().status(500).send())));
    LambdaHandler.setExtensionBaseUrl(server.getAddress().toString());

    AgentSpanContext objTest =
        LambdaHandler.notifyStartInvocation(new TestObject(), "my-lambda-request");

    assertNull(objTest);
    assertEquals(
        "my-lambda-request", server.getLastRequest().getHeader("lambda-runtime-aws-request-id"));

    server.close();
    ct.close();
  }

  @TableTest(
      value = {
        "scenario                     | expected | eHeaderValue | tIdHeaderValue | sIdHeaderValue | sPIdHeaderValue | lambdaResult | boolValue | lambdaReqIdHeaderValue",
        "error with non-string result | true     | 'true'       | '1234'         | '5678'         | 2               |              | true      | 'request123'          ",
        "success with string result   | true     |              | '1234'         | '5678'         | 2               | '12345 '     | false     | 'request456'          "
      })
  void testEndInvocationSuccess(
      boolean expected,
      String eHeaderValue,
      String tIdHeaderValue,
      String sIdHeaderValue,
      String sPIdHeaderValue,
      Object lambdaResult,
      boolean boolValue,
      String lambdaReqIdHeaderValue) {
    JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            "/lambda/end-invocation",
                            api -> api.getResponse().status(200).send())));
    LambdaHandler.setExtensionBaseUrl(server.getAddress().toString());

    DDSpan span = mock(DDSpan.class);
    when(span.getTraceId()).thenReturn(DDTraceId.from("1234"));
    when(span.getSpanId()).thenReturn(DDSpanId.from("5678"));
    when(span.getSamplingPriority()).thenReturn(2);

    boolean result =
        LambdaHandler.notifyEndInvocation(span, lambdaResult, boolValue, lambdaReqIdHeaderValue);

    assertEquals(eHeaderValue, server.getLastRequest().getHeader("x-datadog-invocation-error"));
    assertEquals(tIdHeaderValue, server.getLastRequest().getHeader("x-datadog-trace-id"));
    assertEquals(sIdHeaderValue, server.getLastRequest().getHeader("x-datadog-span-id"));
    assertEquals(sPIdHeaderValue, server.getLastRequest().getHeader("x-datadog-sampling-priority"));
    assertEquals(
        lambdaReqIdHeaderValue, server.getLastRequest().getHeader("lambda-runtime-aws-request-id"));
    assertEquals(expected, result);

    server.close();
  }

  @TableTest(
      value = {
        "scenario                     | expected | headerValue | lambdaResult | boolValue | lambdaReqIdHeaderValue",
        "error with non-string result | false    | 'true'      |              | true      | 'request123'          ",
        "success with string result   | false    |             | '12345'      | false     | 'request456'          "
      })
  void testEndInvocationFailure(
      boolean expected,
      String headerValue,
      Object lambdaResult,
      boolean boolValue,
      String lambdaReqIdHeaderValue) {
    JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            "/lambda/end-invocation",
                            api -> api.getResponse().status(500).send())));
    LambdaHandler.setExtensionBaseUrl(server.getAddress().toString());

    DDSpan span = mock(DDSpan.class);
    when(span.getTraceId()).thenReturn(DDTraceId.from("1234"));
    when(span.getSpanId()).thenReturn(DDSpanId.from("5678"));
    when(span.getSamplingPriority()).thenReturn(2);

    boolean result =
        LambdaHandler.notifyEndInvocation(span, lambdaResult, boolValue, lambdaReqIdHeaderValue);

    assertEquals(expected, result);
    assertEquals(headerValue, server.getLastRequest().getHeader("x-datadog-invocation-error"));
    assertEquals(
        lambdaReqIdHeaderValue, server.getLastRequest().getHeader("lambda-runtime-aws-request-id"));

    server.close();
  }

  @Test
  void testEndInvocationSuccessWithErrorMetadata() {
    JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            "/lambda/end-invocation",
                            api -> api.getResponse().status(200).send())));
    LambdaHandler.setExtensionBaseUrl(server.getAddress().toString());

    DDSpan span = mock(DDSpan.class);
    when(span.getTraceId()).thenReturn(DDTraceId.from("1234"));
    when(span.getSpanId()).thenReturn(DDSpanId.from("5678"));
    when(span.getSamplingPriority()).thenReturn(2);
    when(span.getTag(DDTags.ERROR_MSG)).thenReturn("custom error message");
    when(span.getTag(DDTags.ERROR_TYPE)).thenReturn("java.lang.Throwable");
    when(span.getTag(DDTags.ERROR_STACK)).thenReturn("errorStack\n \ttest");

    LambdaHandler.notifyEndInvocation(span, new Object(), true, "lambda-request-123");

    assertEquals("true", server.getLastRequest().getHeader("x-datadog-invocation-error"));
    assertEquals(
        "custom error message",
        server.getLastRequest().getHeader("x-datadog-invocation-error-msg"));
    assertEquals(
        "java.lang.Throwable",
        server.getLastRequest().getHeader("x-datadog-invocation-error-type"));
    assertEquals(
        "ZXJyb3JTdGFjawogCXRlc3Q=",
        server.getLastRequest().getHeader("x-datadog-invocation-error-stack"));
    assertEquals(
        "lambda-request-123", server.getLastRequest().getHeader("lambda-runtime-aws-request-id"));

    server.close();
  }

  @Test
  void testMoshiToJsonSQSEvent() {
    SQSEvent myEvent = new SQSEvent();
    List<SQSEvent.SQSMessage> records = new ArrayList<>();
    SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
    message.setMessageId("myId");
    message.setAwsRegion("myRegion");
    records.add(message);
    myEvent.setRecords(records);

    String result = LambdaHandler.writeValueAsString(myEvent);

    assertEquals("{\"records\":[{\"awsRegion\":\"myRegion\",\"messageId\":\"myId\"}]}", result);
  }

  @Test
  void testMoshiToJsonS3Event() {
    List<S3EventNotification.S3EventNotificationRecord> list = new ArrayList<>();
    S3EventNotification.S3EventNotificationRecord item0 =
        new S3EventNotification.S3EventNotificationRecord(
            "region", "eventName", "mySource", null, "3.4", null, null, null, null);
    list.add(item0);
    S3Event myEvent = new S3Event(list);

    String result = LambdaHandler.writeValueAsString(myEvent);

    assertEquals(
        "{\"records\":[{\"awsRegion\":\"region\",\"eventName\":\"eventName\",\"eventSource\":\"mySource\",\"eventVersion\":\"3.4\"}]}",
        result);
  }

  @Test
  void testMoshiToJsonSNSEvent() {
    SNSEvent myEvent = new SNSEvent();
    List<SNSEvent.SNSRecord> records = new ArrayList<>();
    SNSEvent.SNSRecord message = new SNSEvent.SNSRecord();
    message.setEventSource("mySource");
    message.setEventVersion("myVersion");
    records.add(message);
    myEvent.setRecords(records);

    String result = LambdaHandler.writeValueAsString(myEvent);

    assertEquals(
        "{\"records\":[{\"eventSource\":\"mySource\",\"eventVersion\":\"myVersion\"}]}", result);
  }

  @Test
  void testMoshiToJsonAPIGatewayProxyRequestEvent() {
    APIGatewayProxyRequestEvent myEvent = new APIGatewayProxyRequestEvent();
    myEvent.setBody("bababango");
    myEvent.setHttpMethod("POST");

    String result = LambdaHandler.writeValueAsString(myEvent);

    assertEquals("{\"body\":\"bababango\",\"httpMethod\":\"POST\"}", result);
  }

  @Test
  void testMoshiToJsonInputStream() {
    String body = "{\"body\":\"bababango\",\"httpMethod\":\"POST\"}";
    ByteArrayInputStream myEvent = new ByteArrayInputStream(body.getBytes());

    String result = LambdaHandler.writeValueAsString(myEvent);

    assertEquals(body, result);
  }

  @Test
  void testMoshiToJsonOutputStream() {
    String body = "{\"body\":\"bababango\",\"statusCode\":\"200\"}";
    ByteArrayOutputStream myEvent = new ByteArrayOutputStream();
    byte[] bodyBytes = body.getBytes();
    myEvent.write(bodyBytes, 0, bodyBytes.length);

    String result = LambdaHandler.writeValueAsString(myEvent);

    assertEquals(body, result);
  }
}
