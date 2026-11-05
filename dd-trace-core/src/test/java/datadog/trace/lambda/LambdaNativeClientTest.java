package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.squareup.moshi.Moshi;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDCoreJavaSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class LambdaNativeClientTest extends DDCoreJavaSpecification {
  private static AgentSpan span() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTraceId()).thenReturn(DDTraceId.fromHex("0123456789abcdefffffffffffffffff"));
    when(span.getSpanId()).thenReturn(-2L);
    when(span.getStartTime()).thenReturn(1700000000000000000L);
    when(span.getDurationNano()).thenReturn(1_000_000L);
    when(span.getServiceName()).thenReturn("function");
    when(span.getResourceName()).thenReturn("handler");
    when(span.forceSamplingDecision()).thenReturn(2);
    when(span.isError()).thenReturn(true);
    return span;
  }

  @Test
  void sendsIdentityBeforeCompletionWithoutPayloadOrLegacyRequests() throws Exception {
    AtomicInteger gets = new AtomicInteger();
    AtomicInteger posts = new AtomicInteger();
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h -> {
                      h.get(
                          LambdaNativeClient.PATH,
                          api -> {
                            gets.incrementAndGet();
                            api.getResponse()
                                .status(200)
                                .send("{\"version\":1,\"native_invocation\":true}");
                          });
                      h.post(
                          LambdaNativeClient.PATH,
                          api -> {
                            posts.incrementAndGet();
                            api.getResponse().status(200).send("{}");
                          });
                    }))) {
      LambdaNativeClient client =
          new LambdaNativeClient(server.getAddress().toString(), new OkHttpClient());
      AgentSpan span = span();
      LambdaNativeClient.Invocation invocation = client.begin(span, 0, "request-1");
      assertTrue(invocation.isAccepted());
      Map<?, ?> begin = body(server);
      assertEquals("0123456789abcdefffffffffffffffff", begin.get("trace_id"));
      assertEquals("fffffffffffffffe", begin.get("span_id"));
      assertEquals("0000000000000000", begin.get("parent_id"));
      assertEquals("begin", begin.get("phase"));
      assertEquals("request-1", begin.get("request_id"));
      assertEquals(10, begin.size());
      invocation.complete(span);
      invocation.complete(span);
      Map<?, ?> end = body(server);
      assertEquals("complete", end.get("phase"));
      assertEquals(Boolean.TRUE, end.get("error"));
      assertEquals(1_000_000d, end.get("duration_ns"));
      client.begin(span, 0, "request-2");
      assertEquals(1, gets.get());
      assertEquals(3, posts.get());
    }
  }

  @TableTest({
    "Version | Native",
    "1       | false ",
    "2       | true  "
  })
  void unsupportedCapabilityDoesNotSendLifecycleMessages(int version, boolean nativeInvocation) {
    AtomicInteger posts = new AtomicInteger();
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h -> {
                      h.get(
                          LambdaNativeClient.PATH,
                          api ->
                              api.getResponse()
                                  .status(200)
                                  .send(
                                      "{\"version\":"
                                          + version
                                          + ",\"native_invocation\":"
                                          + nativeInvocation
                                          + "}"));
                      h.post(
                          LambdaNativeClient.PATH,
                          api -> {
                            posts.incrementAndGet();
                            api.getResponse().status(200).send();
                          });
                    }))) {
      LambdaNativeClient client =
          new LambdaNativeClient(server.getAddress().toString(), new OkHttpClient());
      LambdaNativeClient.Invocation invocation = client.begin(span(), 0, "request-1");
      assertFalse(invocation.isAccepted());
      invocation.complete(span());
      assertEquals(0, posts.get());
    }
  }

  @TableTest({
    "ack                                      | ownership | record",
    "'{\"version\":1,\"dsm_consumed\":false}' | true      | true  ",
    "'{\"version\":1,\"dsm_consumed\":true}'  | true      | false ",
    "'{}'                                     | false     | false ",
    "'{\"version\":2,\"dsm_consumed\":false}' | false     | false "
  })
  void dsmOwnershipRequiresAVersionedAcknowledgment(String ack, boolean ownership, boolean record) {
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h -> {
                      h.get(
                          LambdaNativeClient.PATH,
                          api ->
                              api.getResponse()
                                  .status(200)
                                  .send("{\"version\":1,\"native_invocation\":true}"));
                      h.post(
                          LambdaNativeClient.PATH, api -> api.getResponse().status(200).send(ack));
                    }))) {
      LambdaNativeClient client =
          new LambdaNativeClient(server.getAddress().toString(), new OkHttpClient());
      LambdaNativeClient.Invocation invocation = client.begin(span(), 0, "request");
      assertTrue(invocation.isAccepted());
      assertEquals(ownership, invocation.hasDsmOwnership());
      assertEquals(record, invocation.shouldRecordDsm());
    }
  }

  private static Map<?, ?> body(JavaTestHttpServer server) throws Exception {
    return (Map<?, ?>)
        new Moshi.Builder()
            .build()
            .adapter(Object.class)
            .fromJson(new String(server.getLastRequest().getBody(), StandardCharsets.UTF_8));
  }
}
