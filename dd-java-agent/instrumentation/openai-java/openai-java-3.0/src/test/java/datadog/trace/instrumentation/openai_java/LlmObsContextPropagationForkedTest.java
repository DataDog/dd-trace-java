package datadog.trace.instrumentation.openai_java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.credential.BearerTokenCredential;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.sun.net.httpserver.HttpServer;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that auto-instrumented openai.request spans inherit session_id and the head-based
 * sampling decision from an active LLMObs parent context, and that a stale context left over from
 * an unrelated trace does not leak a sampling verdict onto the span. Forked + @WithConfig used
 * together so the LLMObs system property is in place before the agent installs and there's no
 * leakage from prior test state.
 *
 * <p>The mock OpenAI backend returns a minimal 200 response — the test asserts on the span tag set
 * by OpenAiDecorator.afterStart(), which runs before the HTTP response is parsed, so the response
 * body shape doesn't matter for what's being tested.
 */
@WithConfig(key = "llmobs.enabled", value = "true")
class LlmObsContextPropagationForkedTest extends AbstractInstrumentationTest {

  private static HttpServer mockServer;
  private static OpenAIClient openAiClient;

  @BeforeAll
  static void setupMockOpenAi() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
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

  @Test
  void openAiRequestSpanInheritsSessionIdFromActiveContext() throws Exception {
    String expectedSessionId = "session-propagation-test-abc";

    AgentSpan parentSpan = AgentTracer.startSpan("test", "parent");
    try (ContextScope ignored1 = AgentTracer.activateSpan(parentSpan)) {
      try (ContextScope ignored2 =
          LLMObsContext.attach(parentSpan.spanContext(), expectedSessionId)) {
        try {
          openAiClient.chat().completions().create(buildMinimalChatParams());
        } catch (Exception ignored) {
          // Mock server returns no body — the SDK may throw on parse. The span we care about
          // is already created by the instrumentation advice before this point.
        }
      }
    } finally {
      parentSpan.finish();
    }

    writer.waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(expectedSessionId, openAiSpan.getTag("_ml_obs_tag.session_id"));
  }

  @Test
  void openAiRequestSpanHasNoSessionIdWhenNoLlmObsContext() throws Exception {
    try {
      openAiClient.chat().completions().create(buildMinimalChatParams());
    } catch (Exception ignored) {
      // Mock server returns no body — the SDK may throw on parse. The span we care about
      // is already created by the instrumentation advice before this point.
    }

    writer.waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertNull(openAiSpan.getTag("_ml_obs_tag.session_id"));
  }

  @Test
  void openAiRequestSpanInheritsDroppedSamplingDecisionFromActiveContext() throws Exception {
    AgentSpan parentSpan = AgentTracer.startSpan("test", "parent");
    try (ContextScope ignored1 = AgentTracer.activateSpan(parentSpan)) {
      try (ContextScope ignored2 =
          LLMObsContext.attach(
              parentSpan.spanContext(), null, "0.25", LLMObsContext.SAMPLING_DECISION_DROPPED)) {
        try {
          openAiClient.chat().completions().create(buildMinimalChatParams());
        } catch (Exception ignored) {
        }
      }
    } finally {
      parentSpan.finish();
    }

    writer.waitForTraces(1);
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
              parentSpan.spanContext(), null, "1", LLMObsContext.SAMPLING_DECISION_SAMPLED)) {
        try {
          openAiClient.chat().completions().create(buildMinimalChatParams());
        } catch (Exception ignored) {
        }
      }
    } finally {
      parentSpan.finish();
    }

    writer.waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_SAMPLED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("1", openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }

  @Test
  void openAiRequestSpanHasNoSamplingDecisionWhenNoLlmObsContext() throws Exception {
    try {
      openAiClient.chat().completions().create(buildMinimalChatParams());
    } catch (Exception ignored) {
    }

    // No verdict to inherit, so the span is left unstamped and the mapper's fail-safe retains it.
    writer.waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertNull(openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertNull(openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }

  @Test
  void openAiRequestSpanDoesNotInheritSamplingDecisionFromStaleCrossTraceContext()
      throws Exception {
    // Simulates a stale LLMObsContext leaked across an async boundary: the context is attached,
    // but its span is never made the active tracer span, so the openai.request call below starts
    // a brand-new trace and the trace-consistency gate in OpenAiDecorator must skip inheritance.
    AgentSpan staleParent = AgentTracer.startSpan("test", "stale-parent");
    try (ContextScope ignored =
        LLMObsContext.attach(
            staleParent.spanContext(), null, "0.25", LLMObsContext.SAMPLING_DECISION_DROPPED)) {
      try {
        openAiClient.chat().completions().create(buildMinimalChatParams());
      } catch (Exception ignored2) {
      }
    } finally {
      staleParent.finish();
    }

    writer.waitForTraces(2);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertNull(openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertNull(openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }

  private static ChatCompletionCreateParams buildMinimalChatParams() {
    return ChatCompletionCreateParams.builder()
        .model(ChatModel.GPT_4O_MINI)
        .addSystemMessage("")
        .addUserMessage("")
        .build();
  }

  private static DDSpan findSpanByOperationName(List<List<DDSpan>> traces, String operationName) {
    return traces.stream()
        .flatMap(List::stream)
        .filter(s -> operationName.equals(s.getOperationName().toString()))
        .findFirst()
        .orElse(null);
  }
}
