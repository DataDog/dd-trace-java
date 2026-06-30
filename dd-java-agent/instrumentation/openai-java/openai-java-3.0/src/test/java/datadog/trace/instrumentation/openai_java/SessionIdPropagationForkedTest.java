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
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.junit.utils.config.WithConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that auto-instrumented openai.request spans inherit session_id from an active LLMObs
 * parent context. Forked + @WithConfig used together so the LLMObs system property is in place
 * before the agent installs and there's no leakage from prior test state.
 *
 * <p>The mock OpenAI backend returns a minimal 200 response — the test asserts on the span tag set
 * by OpenAiDecorator.afterStart(), which runs before the HTTP response is parsed, so the response
 * body shape doesn't matter for what's being tested.
 */
@WithConfig(key = "llmobs.enabled", value = "true")
class SessionIdPropagationForkedTest extends AbstractInstrumentationTest {

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
    try (AgentScope ignored1 = AgentTracer.activateSpan(parentSpan)) {
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
