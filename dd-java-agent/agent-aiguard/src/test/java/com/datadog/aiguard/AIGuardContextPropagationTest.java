package com.datadog.aiguard;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.context.ContextScope;
import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AIGuardContextPropagationTest {
  private AgentTracer.TracerAPI originalTracer;
  private CoreTracer tracer;
  private ListWriter writer;

  @BeforeEach
  void setUp() {
    originalTracer = AgentTracer.get();
    writer = new ListWriter();
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
    tracer.close();
  }

  @ParameterizedTest
  @CsvSource({
    "true, ALLOW, 100",
    "false, ALLOW, 100",
    "true, BLOCK, 100",
    "false, BLOCK, 100",
    "true, ERROR, 100",
    "false, ERROR, 100",
    "true, ALLOW, 1",
    "false, ALLOW, 1",
    "true, BLOCK, 1",
    "false, BLOCK, 1",
    "true, ERROR, 1",
    "false, ERROR, 1"
  })
  void isolatesHttpWorkAndRestoresCaller(
      boolean callerPropagation, Outcome outcome, int scopeDepthLimit) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("trace.scope.depth.limit", Integer.toString(scopeDepthLimit));
    tracer =
        CoreTracer.builder()
            .withProperties(properties)
            .writer(writer)
            .strictTraceWrites(true)
            .build();
    AgentTracer.forceRegister(tracer);
    HttpUrl url = HttpUrl.get("http://localhost/evaluate");
    OkHttpClient client = mock(OkHttpClient.class);
    Call call = mock(Call.class);
    when(client.newCall(any(Request.class))).thenReturn(call);
    when(call.execute())
        .thenAnswer(
            invocation -> {
              DDSpan span = (DDSpan) tracer.activeSpan();
              assertEquals(
                  scopeDepthLimit == 1 ? "parent" : "ai_guard", span.getOperationName().toString());
              assertFalse(tracer.isAsyncPropagationEnabled());
              if (outcome == Outcome.ERROR) {
                throw new IOException("Transport failed");
              }
              String action = outcome == Outcome.BLOCK ? "DENY" : "ALLOW";
              return new Response.Builder()
                  .request(new Request.Builder().url(url).build())
                  .protocol(Protocol.HTTP_1_1)
                  .code(200)
                  .message("OK")
                  .body(
                      ResponseBody.create(
                          MediaType.get("application/json"),
                          "{\"data\":{\"attributes\":{\"action\":\""
                              + action
                              + "\",\"is_blocking_enabled\":true}}}"))
                  .build();
            });
    AIGuardInternal evaluator = new AIGuardInternal(url, emptyMap(), client);
    List<AIGuard.Message> messages = singletonList(AIGuard.Message.message("user", "Hello"));
    AgentSpan parent = tracer.startSpan("test", "parent");
    try (ContextScope ignored = tracer.activateSpan(parent)) {
      tracer.setAsyncPropagationEnabled(callerPropagation);
      if (outcome == Outcome.ERROR) {
        assertThrows(
            AIGuard.AIGuardClientError.class,
            () -> evaluator.evaluate(messages, AIGuard.Options.DEFAULT));
      } else if (outcome == Outcome.BLOCK) {
        assertThrows(
            AIGuard.AIGuardAbortError.class,
            () -> evaluator.evaluate(messages, AIGuard.Options.DEFAULT));
      } else {
        assertEquals(
            AIGuard.Action.ALLOW,
            evaluator.evaluate(messages, AIGuard.Options.DEFAULT).getAction());
      }
      assertSame(parent, tracer.activeSpan());
      assertEquals(callerPropagation, tracer.isAsyncPropagationEnabled());
      verify(call).execute();
    } finally {
      parent.finish();
    }
    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    assertEquals(2, writer.get(0).size());
    DDSpan evaluation =
        writer.get(0).stream()
            .filter(span -> "ai_guard".contentEquals(span.getOperationName()))
            .findFirst()
            .get();
    assertEquals(parent.getSpanId(), evaluation.getParentId());
    assertTrue(evaluation.isFinished());
  }

  enum Outcome {
    ALLOW,
    BLOCK,
    ERROR
  }
}
