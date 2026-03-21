package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LambdaHandlerTest extends DDCoreSpecification {

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

  private static HttpServer createServer(int statusCode, Map<String, String> responseHeaders)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
              exchange.getResponseHeaders().set(entry.getKey(), entry.getValue());
            }
            if (statusCode == 200) {
              exchange.sendResponseHeaders(statusCode, 0);
            } else {
              exchange.sendResponseHeaders(statusCode, -1);
            }
            exchange.close();
          }
        });
    server.start();
    return server;
  }

  @Test
  void testStartInvocationSuccess() throws Exception {
    CoreTracer ct = tracerBuilder().build();

    Map<String, String> responseHeaders = new HashMap<>();
    responseHeaders.put("x-datadog-trace-id", "1234");
    responseHeaders.put("x-datadog-sampling-priority", "2");
    HttpServer server = createServer(200, responseHeaders);

    LambdaHandler.setExtensionBaseUrl("http://localhost:" + server.getAddress().getPort());

    try {
      AgentSpanContext objTest =
          LambdaHandler.notifyStartInvocation(new TestObject(), "lambda-request-123");
      assertEquals("1234", objTest.getTraceId().toString());
      assertEquals(2, objTest.getSamplingPriority());
    } finally {
      server.stop(0);
      ct.close();
    }
  }

  @Test
  void testStartInvocationWith128BitTraceId() throws Exception {
    CoreTracer ct = tracerBuilder().build();

    Map<String, String> responseHeaders = new HashMap<>();
    responseHeaders.put("x-datadog-trace-id", "5744042798732701615");
    responseHeaders.put("x-datadog-sampling-priority", "2");
    responseHeaders.put("x-datadog-tags", "_dd.p.tid=1914fe7789eb32be");
    HttpServer server = createServer(200, responseHeaders);

    LambdaHandler.setExtensionBaseUrl("http://localhost:" + server.getAddress().getPort());

    try {
      AgentSpanContext objTest =
          LambdaHandler.notifyStartInvocation(new TestObject(), "lambda-request-123");
      assertEquals("1914fe7789eb32be4fb6f07e011a6faf", objTest.getTraceId().toHexString());
      assertEquals(2, objTest.getSamplingPriority());
    } finally {
      server.stop(0);
      ct.close();
    }
  }

  @Test
  void testStartInvocationFailure() throws Exception {
    CoreTracer ct = tracerBuilder().build();

    HttpServer server = createServer(500, Collections.emptyMap());
    LambdaHandler.setExtensionBaseUrl("http://localhost:" + server.getAddress().getPort());

    try {
      AgentSpanContext objTest =
          LambdaHandler.notifyStartInvocation(new TestObject(), "my-lambda-request");
      assertNull(objTest);
    } finally {
      server.stop(0);
      ct.close();
    }
  }

  @Test
  void testEndInvocationSuccessWithError() throws Exception {
    HttpServer server = createServer(200, Collections.emptyMap());
    AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          Map<String, String> headers = new HashMap<>();
          for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (!entry.getValue().isEmpty()) {
              headers.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
            }
          }
          capturedHeaders.set(headers);
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });

    LambdaHandler.setExtensionBaseUrl("http://localhost:" + server.getAddress().getPort());

    DDSpan span = Mockito.mock(DDSpan.class);
    Mockito.when(span.getTraceId()).thenReturn(DDTraceId.from("1234"));
    Mockito.when(span.getSpanId()).thenReturn(DDSpanId.from("5678"));
    Mockito.when(span.getSamplingPriority()).thenReturn(2);

    try {
      boolean result = LambdaHandler.notifyEndInvocation(span, new Object(), true, "request123");
      assertTrue(result);
      Map<String, String> headers = capturedHeaders.get();
      assertEquals("true", headers.get("x-datadog-invocation-error"));
      assertEquals("1234", headers.get("x-datadog-trace-id"));
      assertEquals("5678", headers.get("x-datadog-span-id"));
      assertEquals("2", headers.get("x-datadog-sampling-priority"));
      assertEquals("request123", headers.get("lambda-runtime-aws-request-id"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void testEndInvocationSuccessWithoutError() throws Exception {
    HttpServer server = createServer(200, Collections.emptyMap());
    AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          Map<String, String> headers = new HashMap<>();
          for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (!entry.getValue().isEmpty()) {
              headers.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
            }
          }
          capturedHeaders.set(headers);
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });

    LambdaHandler.setExtensionBaseUrl("http://localhost:" + server.getAddress().getPort());

    DDSpan span = Mockito.mock(DDSpan.class);
    Mockito.when(span.getTraceId()).thenReturn(DDTraceId.from("1234"));
    Mockito.when(span.getSpanId()).thenReturn(DDSpanId.from("5678"));
    Mockito.when(span.getSamplingPriority()).thenReturn(2);

    try {
      boolean result = LambdaHandler.notifyEndInvocation(span, "12345", false, "request456");
      assertTrue(result);
      Map<String, String> headers = capturedHeaders.get();
      assertNull(headers.get("x-datadog-invocation-error"));
      assertEquals("request456", headers.get("lambda-runtime-aws-request-id"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void testEndInvocationFailure() throws Exception {
    HttpServer server = createServer(500, Collections.emptyMap());
    AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          Map<String, String> headers = new HashMap<>();
          for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (!entry.getValue().isEmpty()) {
              headers.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
            }
          }
          capturedHeaders.set(headers);
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });

    LambdaHandler.setExtensionBaseUrl("http://localhost:" + server.getAddress().getPort());

    DDSpan span = Mockito.mock(DDSpan.class);
    Mockito.when(span.getTraceId()).thenReturn(DDTraceId.from("1234"));
    Mockito.when(span.getSpanId()).thenReturn(DDSpanId.from("5678"));
    Mockito.when(span.getSamplingPriority()).thenReturn(2);

    try {
      boolean resultWithError =
          LambdaHandler.notifyEndInvocation(span, new Object(), true, "request123");
      org.junit.jupiter.api.Assertions.assertFalse(resultWithError);
      assertEquals("true", capturedHeaders.get().get("x-datadog-invocation-error"));
      assertEquals("request123", capturedHeaders.get().get("lambda-runtime-aws-request-id"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void testEndInvocationSuccessWithErrorMetadata() throws Exception {
    HttpServer server = createServer(200, Collections.emptyMap());
    AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          Map<String, String> headers = new HashMap<>();
          for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (!entry.getValue().isEmpty()) {
              headers.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
            }
          }
          capturedHeaders.set(headers);
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });

    LambdaHandler.setExtensionBaseUrl("http://localhost:" + server.getAddress().getPort());

    DDSpan span = Mockito.mock(DDSpan.class);
    Mockito.when(span.getTraceId()).thenReturn(DDTraceId.from("1234"));
    Mockito.when(span.getSpanId()).thenReturn(DDSpanId.from("5678"));
    Mockito.when(span.getSamplingPriority()).thenReturn(2);
    Mockito.when(span.getTag(DDTags.ERROR_MSG)).thenReturn("custom error message");
    Mockito.when(span.getTag(DDTags.ERROR_TYPE)).thenReturn("java.lang.Throwable");
    Mockito.when(span.getTag(DDTags.ERROR_STACK)).thenReturn("errorStack\n \ttest");

    try {
      LambdaHandler.notifyEndInvocation(span, new Object(), true, "lambda-request-123");
      Map<String, String> headers = capturedHeaders.get();
      assertEquals("true", headers.get("x-datadog-invocation-error"));
      assertEquals("custom error message", headers.get("x-datadog-invocation-error-msg"));
      assertEquals("java.lang.Throwable", headers.get("x-datadog-invocation-error-type"));
      assertEquals("ZXJyb3JTdGFjawogCXRlc3Q=", headers.get("x-datadog-invocation-error-stack"));
      assertEquals("lambda-request-123", headers.get("lambda-runtime-aws-request-id"));
    } finally {
      server.stop(0);
    }
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
  void testMoshiToJsonOutputStream() throws Exception {
    String body = "{\"body\":\"bababango\",\"statusCode\":\"200\"}";
    ByteArrayOutputStream myEvent = new ByteArrayOutputStream();
    myEvent.write(body.getBytes(), 0, body.length());

    String result = LambdaHandler.writeValueAsString(myEvent);
    assertEquals(body, result);
  }
}
