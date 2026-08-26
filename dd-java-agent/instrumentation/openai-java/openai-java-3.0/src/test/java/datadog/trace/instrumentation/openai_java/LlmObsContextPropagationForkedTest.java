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
 * Mock OpenAI backend and request helpers, shared by the LLMObs forked tests in this file.
 *
 * <p>Subclasses differ only in the {@code @WithConfig} values they declare. One class per
 * configuration: {@code OpenAiDecorator} reads the LLMObs config once when its {@code DECORATE}
 * singleton initializes, and {@code forkedTest} forks per test class ({@code forkEvery = 1}).
 */
abstract class AbstractLlmObsOpenAiForkedTest extends AbstractInstrumentationTest {

  protected static HttpServer mockServer;
  protected static OpenAIClient openAiClient;

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

  protected static ChatCompletionCreateParams buildMinimalChatParams() {
    return ChatCompletionCreateParams.builder()
        .model(ChatModel.GPT_4O_MINI)
        .addSystemMessage("")
        .addUserMessage("")
        .build();
  }

  protected static DDSpan findSpanByOperationName(List<List<DDSpan>> traces, String operationName) {
    return traces.stream()
        .flatMap(List::stream)
        .filter(s -> operationName.equals(s.getOperationName().toString()))
        .findFirst()
        .orElse(null);
  }
}

/**
 * Verifies that auto-instrumented openai.request spans inherit session_id and the head-based
 * sampling decision from an active LLMObs parent context, that they compute a verdict of their own
 * when there is no parent to inherit from, and that a stale context left over from an unrelated
 * trace does not leak a sampling verdict onto the span. Forked + @WithConfig used together so the
 * LLMObs system property is in place before the agent installs and there's no leakage from prior
 * test state.
 *
 * <p>Runs at the default sample rate of 1.0. Drop-side coverage lives in {@link
 * LlmObsZeroSampleRateForkedTest}.
 *
 * <p>The mock OpenAI backend returns a minimal 200 response — the test asserts on the span tag set
 * by OpenAiDecorator.afterStart(), which runs before the HTTP response is parsed, so the response
 * body shape doesn't matter for what's being tested.
 */
@WithConfig(key = "llmobs.enabled", value = "true")
class LlmObsContextPropagationForkedTest extends AbstractLlmObsOpenAiForkedTest {

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
  void openAiRequestSpanComputesItsOwnSamplingDecisionWhenNoLlmObsContext() throws Exception {
    try {
      openAiClient.chat().completions().create(buildMinimalChatParams());
    } catch (Exception ignored) {
    }

    // No verdict to inherit, so the span is the root of its own LLMObs trace and decides for
    // itself. The rate of 1.0 retains every trace ID, so the verdict is deterministic without
    // controlling the trace ID.
    writer.waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_SAMPLED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("1", openAiSpan.getTag("_ml_obs_tag.sample_rate"));
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

    // The stale "0"/"0.25" pair must not leak; the span falls through to deciding for itself at
    // the configured rate of 1.0 instead.
    writer.waitForTraces(2);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_SAMPLED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("1", openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }
}

/**
 * Verifies that an auto-instrumented openai.request span with no LLMObs parent is stamped as
 * dropped when the sample rate is 0.
 */
@WithConfig(key = "llmobs.enabled", value = "true")
@WithConfig(key = "llmobs.sample.rate", value = "0")
class LlmObsZeroSampleRateForkedTest extends AbstractLlmObsOpenAiForkedTest {

  @Test
  void parentlessOpenAiRequestSpanIsDroppedAtZeroSampleRate() throws Exception {
    try {
      openAiClient.chat().completions().create(buildMinimalChatParams());
    } catch (Exception ignored) {
    }

    writer.waitForTraces(1);
    DDSpan openAiSpan = findSpanByOperationName(writer, "openai.request");
    assertNotNull(openAiSpan, "openai.request span should have been created");
    assertEquals(
        LLMObsContext.SAMPLING_DECISION_DROPPED,
        openAiSpan.getTag("_ml_obs_tag.sampling_decision"));
    assertEquals("0", openAiSpan.getTag("_ml_obs_tag.sample_rate"));
  }
}
