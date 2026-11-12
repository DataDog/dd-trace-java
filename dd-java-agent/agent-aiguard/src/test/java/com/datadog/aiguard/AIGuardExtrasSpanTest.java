package com.datadog.aiguard;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.Test;

/** Checks the backend extras as stored by a real span, which a mocked span cannot show. */
class AIGuardExtrasSpanTest {

  private static final HttpUrl URL = HttpUrl.get("http://localhost/evaluate");

  private AgentTracer.TracerAPI originalTracer;
  private CoreTracer tracer;
  private ListWriter writer;

  @BeforeEach
  void setUp() {
    originalTracer = AgentTracer.get();
    writer = new ListWriter();
    tracer = CoreTracer.builder().writer(writer).strictTraceWrites(true).build();
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
    tracer.close();
  }

  @Test
  void keepsEmptyStringTags() throws Exception {
    DDSpan span = evaluate("[{\"key\":\"custom_context.note\",\"value\":\"\"}]", "[]");

    assertEquals("", span.getTag("custom_context.note"));
  }

  @Test
  void lastDuplicateWinsAcrossTagAliases() throws Exception {
    // user_agent.original is the OpenTelemetry name of http.useragent: both set the same tag
    DDSpan span =
        evaluate(
            "[{\"key\":\"http.useragent\",\"value\":\"first\"},"
                + "{\"key\":\"user_agent.original\",\"value\":\"second\"},"
                + "{\"key\":\"http.useragent\",\"value\":\"third\"}]",
            "[]");

    assertEquals("third", span.getTag("http.useragent"));
  }

  @Test
  void addsExtraMetaStructNextToTracerFields() throws Exception {
    DDSpan span =
        evaluate(
            "[]",
            "[{\"key\":\"policy_match\",\"value\":{\"rule\":\"credit_card\"}},"
                + "{\"key\":\"flag\",\"value\":false}]");

    @SuppressWarnings("unchecked")
    Map<String, Object> metaStruct = (Map<String, Object>) span.getMetaStruct().get("ai_guard");
    assertEquals(singletonMap("rule", "credit_card"), metaStruct.get("policy_match"));
    assertEquals(false, metaStruct.get("flag"));
    assertEquals(1, ((List<?>) metaStruct.get("messages")).size());
  }

  @Test
  void neverOverwritesTheEvaluation() throws Exception {
    DDSpan span =
        evaluate(
            "DENY",
            "[{\"key\":\"ai_guard.action\",\"value\":\"ALLOW\"},"
                + "{\"key\":\"ai_guard.reason\",\"value\":\"forged\"},"
                + "{\"key\":\"ai_guard.redacted\",\"value\":\"true\"}]",
            "[{\"key\":\"attack_categories\",\"value\":\"forged\"},"
                + "{\"key\":\"messages\",\"value\":\"forged\"}]");

    assertEquals("DENY", String.valueOf(span.getTag("ai_guard.action")));
    assertEquals("It is fine", span.getTag("ai_guard.reason"));
    assertEquals(false, span.getTag("ai_guard.redacted"));
    @SuppressWarnings("unchecked")
    Map<String, Object> metaStruct = (Map<String, Object>) span.getMetaStruct().get("ai_guard");
    assertFalse(metaStruct.containsKey("attack_categories"));
    assertEquals(1, ((List<?>) metaStruct.get("messages")).size());
  }

  private DDSpan evaluate(String extraTags, String extraMetaStruct) throws Exception {
    return evaluate("ALLOW", extraTags, extraMetaStruct);
  }

  private DDSpan evaluate(String action, String extraTags, String extraMetaStruct)
      throws Exception {
    String body =
        "{\"data\":{\"attributes\":{\"action\":\""
            + action
            + "\",\"reason\":\"It is fine\","
            + "\"extra_tags\":"
            + extraTags
            + ",\"extra_metastruct\":"
            + extraMetaStruct
            + "}}}";
    OkHttpClient client = mock(OkHttpClient.class);
    Call call = mock(Call.class);
    when(client.newCall(any(Request.class))).thenReturn(call);
    when(call.execute())
        .thenAnswer(invocation ->
            new Response.Builder()
                .request(new Request.Builder().url(URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("application/json"), body))
                .build());
    AIGuardInternal evaluator = new AIGuardInternal(URL, emptyMap(), client);

    evaluator.evaluate(
        singletonList(AIGuard.Message.message("user", "Hello")), AIGuard.Options.DEFAULT);

    writer.waitForTraces(1);
    return writer.get(0).stream()
        .filter(span -> "ai_guard".contentEquals(span.getOperationName()))
        .findFirst()
        .get();
  }
}
