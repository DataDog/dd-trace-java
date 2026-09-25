package datadog.trace.instrumentation.openai_java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.credential.BearerTokenCredential;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import datadog.context.ContextScope;
import datadog.environment.OperatingSystem;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.llmobs.GenAiApmTags;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Mock OpenAI backend and request helpers, shared by the LLMObs forked tests in this file.
 *
 * <p>Subclasses differ only in the {@code @WithConfig} values they declare. One class per
 * configuration: {@code OpenAiDecorator} reads the LLMObs config once when its {@code DECORATE}
 * singleton initializes, and {@code forkedTest} forks per test class ({@code forkEvery = 1}).
 */
abstract class AbstractLlmObsOpenAiForkedTest extends AbstractInstrumentationTest {

  private static final int WINDOWS_TRACE_TIMEOUT_SECONDS = 90;

  protected static HttpServer mockServer;
  protected static OpenAIClient openAiClient;

  private static final String CHAT_COMPLETION_BODY =
      "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1,"
          + "\"model\":\"gpt-4o-mini-2024-07-18\","
          + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"logprobs\":null,"
          + "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
          + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18,"
          + "\"prompt_tokens_details\":{\"cached_tokens\":4}}}";

  private static final String EMBEDDING_BODY =
      "{\"object\":\"list\",\"model\":\"text-embedding-ada-002\","
          + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],"
          + "\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5}}";

  @BeforeAll
  static void setupMockOpenAi() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    mockServer.createContext("/v1/chat/completions", jsonHandler(CHAT_COMPLETION_BODY));
    mockServer.createContext("/v1/embeddings", jsonHandler(EMBEDDING_BODY));
    mockServer.createContext(
        "/v1/",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    mockServer.start();

    openAiClient =
        OpenAIOkHttpClient.builder()
            .baseUrl(
                "http://"
                    + mockServer.getAddress().getHostString()
                    + ":"
                    + mockServer.getAddress().getPort()
                    + "/v1")
            .credential(BearerTokenCredential.create(""))
            .build();
  }

  @AfterAll
  static void tearDownMockOpenAi() {
    if (mockServer != null) {
      mockServer.stop(0);
      mockServer = null;
    }
    openAiClient = null;
  }

  private static HttpHandler jsonHandler(String body) {
    return exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    };
  }

  protected static ChatCompletionCreateParams buildMinimalChatParams() {
    return ChatCompletionCreateParams.builder()
        .model(ChatModel.GPT_4O_MINI)
        .addSystemMessage("")
        .addUserMessage("")
        .build();
  }

  protected static EmbeddingCreateParams buildMinimalEmbeddingParams() {
    return EmbeddingCreateParams.builder()
        .model(EmbeddingModel.TEXT_EMBEDDING_ADA_002)
        .input("")
        .build();
  }

  protected static DDSpan findSpanByOperationName(List<List<DDSpan>> traces, String operationName) {
    return traces.stream()
        .flatMap(List::stream)
        .filter(s -> operationName.equals(s.getOperationName().toString()))
        .findFirst()
        .orElse(null);
  }

  /**
   * A fresh OpenAI test process can take longer than the default 20-second trace timeout on Windows
   * CI. Allow up to 90 seconds there while retaining the default timeout elsewhere.
   */
  protected void waitForTraces(int count) throws Exception {
    if (OperatingSystem.isWindows()) {
      if (!writer.waitForTracesMax(count, WINDOWS_TRACE_TIMEOUT_SECONDS)) {
        throw new AssertionError("Timeout waiting for " + count + " OpenAI trace(s)");
      }
    } else {
      writer.waitForTraces(count);
    }
  }
}

/**
 * Verifies that auto-instrumented openai.request spans inherit session_id, agent_version and the
 * head-based sampling decision from an active LLMObs parent context, that they compute a sampling
 * verdict of their own when there is no parent to inherit from, and that a stale context left over
 * from an unrelated trace leaks none of the three onto the span. Forked + @WithConfig used together
 * so the LLMObs system property is in place before the agent installs and there's no leakage from
 * prior test state.
 *
 * <p>Runs at the default sample rate of 1.0. Drop-side coverage lives in {@link
 * LlmObsZeroSampleRateForkedTest}.
 *
 * <p>The tests assert on span tags set by OpenAiDecorator.afterStart(), which runs before the HTTP
 * response is parsed, so the mock response body doesn't matter for what's being tested.
 */
@WithConfig(key = "llmobs.enabled", value = "true")
@DisabledOnOs(
    value = OS.WINDOWS,
    disabledReason = "The first OpenAI request does not emit its trace on Windows CI")
class LlmObsContextPropagationForkedTest extends AbstractLlmObsOpenAiForkedTest {

  @Test
  void openAiRequestSpanInheritsSessionIdFromActiveContext() throws Exception {
    String expectedSessionId = "session-propagation-test-abc";

    AgentSpan parentSpan = AgentTracer.startSpan("test", "parent");
    try (ContextScope ignored1 = AgentTracer.activateSpan(parentSpan)) {
      try (ContextScope ignored2 =
          LLMObsContext.attach(parentSpan.spanContext(), expectedSessionId)) {
        openAiClient.chat().completions().create(buildMinimalChatParams());
      }
    } finally {
      parentSpan.finish();
    }

    waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(expectedSessionId, openAiSpan.getTag("_ml_obs_tag.session_id"));
  }

  @Test
  void openAiRequestSpanHasNoSessionIdWhenNoLlmObsContext() throws Exception {
    openAiClient.chat().completions().create(buildMinimalChatParams());

    waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertNull(openAiSpan.getTag("_ml_obs_tag.session_id"));
  }

  @Test
  void openAiRequestSpanInheritsAgentVersionFromActiveContext() throws Exception {
    String expectedAgentVersion = "agent-version-propagation-test-1.2.3";

    AgentSpan parentSpan = AgentTracer.startSpan("test", "parent");
    try (ContextScope ignored1 = AgentTracer.activateSpan(parentSpan)) {
      try (ContextScope ignored2 =
          LLMObsContext.attach(parentSpan.spanContext(), null, expectedAgentVersion)) {
        openAiClient.chat().completions().create(buildMinimalChatParams());
      }
    } finally {
      parentSpan.finish();
    }

    waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(expectedAgentVersion, openAiSpan.getTag("_ml_obs_tag.agent_version"));
  }

  @Test
  void openAiRequestSpanInheritsDroppedSamplingDecisionFromActiveContext() throws Exception {
    AgentSpan parentSpan = AgentTracer.startSpan("test", "parent");
    try (ContextScope ignored1 = AgentTracer.activateSpan(parentSpan)) {
      try (ContextScope ignored2 =
          LLMObsContext.attach(
              parentSpan.spanContext(),
              null,
              null,
              "0.25",
              LLMObsContext.SAMPLING_DECISION_DROPPED,
              null,
              null)) {
        openAiClient.chat().completions().create(buildMinimalChatParams());
      }
    } finally {
      parentSpan.finish();
    }

    waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_DROPPED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("0.25", openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }

  @Test
  void openAiRequestSpanInheritsRetainedSamplingDecisionFromActiveContext() throws Exception {
    AgentSpan parentSpan = AgentTracer.startSpan("test", "parent");
    try (ContextScope ignored1 = AgentTracer.activateSpan(parentSpan)) {
      try (ContextScope ignored2 =
          LLMObsContext.attach(
              parentSpan.spanContext(),
              null,
              null,
              "1",
              LLMObsContext.SAMPLING_DECISION_SAMPLED,
              null,
              null)) {
        openAiClient.chat().completions().create(buildMinimalChatParams());
      }
    } finally {
      parentSpan.finish();
    }

    waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_SAMPLED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("1", openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }

  @Test
  void openAiRequestSpanComputesItsOwnSamplingDecisionWhenNoLlmObsContext() throws Exception {
    openAiClient.chat().completions().create(buildMinimalChatParams());

    // No verdict to inherit, so the span is the root of its own LLMObs trace and decides for
    // itself. The rate of 1.0 retains every trace ID, so the verdict is deterministic without
    // controlling the trace ID.
    waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_SAMPLED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("1", openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }

  @Test
  void openAiRequestSpanInheritsNothingFromStaleCrossTraceContext() throws Exception {
    // Simulates a stale LLMObsContext leaked across an async boundary: the context is attached,
    // but its span is never made the active tracer span, so the openai.request call below starts
    // a brand-new trace and the trace-consistency gate in OpenAiDecorator must skip inheritance.
    AgentSpan staleParent = AgentTracer.startSpan("test", "stale-parent");
    try (ContextScope ignored =
        LLMObsContext.attach(
            staleParent.spanContext(),
            "stale-session",
            "stale-version",
            "0.25",
            LLMObsContext.SAMPLING_DECISION_DROPPED,
            "stale-agent-span-id",
            "stale-agent")) {
      openAiClient.chat().completions().create(buildMinimalChatParams());
    } finally {
      staleParent.finish();
    }

    waitForTraces(2);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");

    // The stale "0"/"0.25" pair must not leak; the span falls through to deciding for itself at
    // the configured rate of 1.0 instead.
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_SAMPLED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("1", openAiSpan.getTag("_ml_obs_tag.sample_rate"));

    // The same gate covers parent_id, session_id, agent_version and agent attribution: inheriting
    // any of them would point this span at a parent in an unrelated trace and file it under an
    // unrelated session or agent.
    assertEquals(LLMObsContext.ROOT_SPAN_ID, openAiSpan.getTag("_ml_obs_tag.parent_id"));
    assertNull(openAiSpan.getTag("_ml_obs_tag.session_id"));
    assertNull(openAiSpan.getTag("_ml_obs_tag.agent_version"));
    assertNull(openAiSpan.getTag("_ml_obs_tag.pagent_span_id"));
    assertNull(openAiSpan.getTag("_ml_obs_tag.pagent_name"));
  }
}

/**
 * Verifies that an auto-instrumented openai.request span with no LLMObs parent is stamped as
 * dropped when the sample rate is 0.
 */
@WithConfig(key = "llmobs.enabled", value = "true")
@WithConfig(key = "llmobs.sample.rate", value = "0")
@DisabledOnOs(
    value = OS.WINDOWS,
    disabledReason = "The first OpenAI request does not emit its trace on Windows CI")
class LlmObsZeroSampleRateForkedTest extends AbstractLlmObsOpenAiForkedTest {

  @Test
  void parentlessOpenAiRequestSpanIsDroppedAtZeroSampleRate() throws Exception {
    openAiClient.chat().completions().create(buildMinimalChatParams());

    waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_DROPPED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("0", openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }
}

/**
 * Verifies the gen_ai.* attributes an openai.request span carries with LLM Observability disabled:
 * operation, model, provider, application and token usage, but no conversation id and none of the
 * LLM Observability tags.
 */
@WithConfig(key = "llmobs.enabled", value = "false")
class LlmObsDisabledForkedTest extends AbstractLlmObsOpenAiForkedTest {

  @Test
  void chatCompletionEmitsTheGenAiAttributesAvailableWithoutLlmObs() {
    openAiClient.chat().completions().create(buildMinimalChatParams());

    DDSpan openAiSpan = awaitOpenAiSpan("/v1/chat/completions");

    assertEquals("llm", openAiSpan.getTag("gen_ai.operation.name"));
    assertEquals("gpt-4o-mini-2024-07-18", openAiSpan.getTag("gen_ai.request.model"));
    assertEquals("openai", openAiSpan.getTag("gen_ai.provider.name"));
    assertNotNull(openAiSpan.getTag("gen_ai.application.name"));
    assertEquals("true", openAiSpan.getTag(GenAiApmTags.ARTIFICIAL_TAGS));

    assertEquals(11.0, openAiSpan.getTag("gen_ai.usage.input_tokens"));
    assertEquals(7.0, openAiSpan.getTag("gen_ai.usage.output_tokens"));
    assertEquals(18.0, openAiSpan.getTag("gen_ai.usage.total_tokens"));
    assertEquals(4.0, openAiSpan.getTag("gen_ai.usage.cache_read_input_tokens"));

    assertNull(openAiSpan.getTag("gen_ai.conversation.id"));
    assertNull(openAiSpan.getTag("_ml_obs_tag.span.kind"));
    assertNull(openAiSpan.getTag("_ml_obs_metric.input_tokens"));
  }

  @Test
  void embeddingMapsToTheEmbeddingOperation() {
    openAiClient.embeddings().create(buildMinimalEmbeddingParams());

    DDSpan openAiSpan = awaitOpenAiSpan("/v1/embeddings");

    assertEquals("embedding", openAiSpan.getTag("gen_ai.operation.name"));
    assertEquals(
        openAiSpan.getTag("openai.request.model"), openAiSpan.getTag("gen_ai.request.model"));
    assertEquals("openai", openAiSpan.getTag("gen_ai.provider.name"));

    assertEquals(5.0, openAiSpan.getTag("gen_ai.usage.input_tokens"));
    assertEquals(5.0, openAiSpan.getTag("gen_ai.usage.total_tokens"));
    assertNull(openAiSpan.getTag("gen_ai.usage.output_tokens"));
  }

  // Both tests here produce an openai.request span, so match on the endpoint rather than take the
  // first one written: a trace arriving late from the sibling test would otherwise be picked up.
  private DDSpan awaitOpenAiSpan(String endpoint) {
    blockUntilTracesMatch(traces -> findOpenAiSpan(traces, endpoint) != null);
    DDSpan span = findOpenAiSpan(writer, endpoint);
    assertNotNull(span, "openai.request span for " + endpoint + " should have been created");
    return span;
  }

  private static DDSpan findOpenAiSpan(List<List<DDSpan>> traces, String endpoint) {
    return traces.stream()
        .flatMap(List::stream)
        .filter(span -> "openai.request".equals(span.getOperationName().toString()))
        .filter(span -> endpoint.equals(span.getTag("openai.request.endpoint")))
        .findFirst()
        .orElse(null);
  }
}
