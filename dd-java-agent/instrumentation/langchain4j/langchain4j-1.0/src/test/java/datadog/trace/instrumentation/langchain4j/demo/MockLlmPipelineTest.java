package datadog.trace.instrumentation.langchain4j.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class MockLlmPipelineTest {

  interface WeatherAssistant {
    String chat(String message);
  }

  static class MockWeatherTool {
    @Tool("Get current weather for a location")
    public String getWeather(String location) {
      return "Sunny, 22°C in " + location;
    }
  }

  static class TwoTurnMockModel implements ChatModel {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResponse chat(ChatRequest request) {
      try {
        Thread.sleep(30);
      } catch (InterruptedException ignored) {
      }
      if (calls.getAndIncrement() == 0) {
        return ChatResponse.builder()
            .aiMessage(
                AiMessage.from(
                    ToolExecutionRequest.builder()
                        .name("getWeather")
                        .arguments("{\"location\": \"Amsterdam\"}")
                        .build()))
            .build();
      }
      return ChatResponse.builder()
          .aiMessage(AiMessage.from("The weather in Amsterdam is Sunny, 22°C."))
          .build();
    }
  }

  // Smoke test: verifies the mock pipeline executes and returns a response.
  // This test runs without the agent, so ByteBuddy advice is never applied.
  // For JFR event-level verification see LlmJfrEventTest.
  @Test
  public void pipelineReturnsExpectedResponse() {
    WeatherAssistant assistant =
        AiServices.builder(WeatherAssistant.class)
            .chatModel(new TwoTurnMockModel())
            .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
            .tools(new MockWeatherTool())
            .build();

    String response = assistant.chat("What is the weather in Amsterdam?");
    assertNotNull(response, "Expected a non-null response from the mock pipeline");
  }
}
