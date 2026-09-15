package datadog.trace.instrumentation.langchain4j.demo;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import java.time.Duration;

/**
 * Exercises LangChain4j instrumentation against a local Ollama server.
 *
 * <p>Emits three JFR event types per pipeline turn:
 *
 * <ul>
 *   <li>{@code datadog.AiService} — AI service method invocation
 *   <li>{@code datadog.ChatModel} — blocking LLM chat call
 *   <li>{@code datadog.ToolExecutor} — tool execution (when tools are used)
 * </ul>
 *
 * Run:
 *
 * <pre>
 *   ./gradlew :dd-java-agent:instrumentation:langchain4j:langchain4j-1.0:runOllamaDemo
 * </pre>
 *
 * Prerequisites: {@code ollama serve && ollama pull llama3}
 */
public class OllamaLlmPipelineDemo {

  interface Assistant {
    String chat(String message);
  }

  public static void main(String[] args) {
    OllamaChatModel model =
        OllamaChatModel.builder()
            .baseUrl("http://localhost:11434")
            .modelName("llama3")
            .timeout(Duration.ofMinutes(2))
            .build();

    Assistant assistant =
        AiServices.builder(Assistant.class)
            .chatModel(model)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
            .build();

    String[] questions = {
      "Explain Java garbage collection in one sentence.",
      "What is a Java virtual thread?",
      "Name one advantage of the G1 garbage collector."
    };
    for (String q : questions) {
      System.out.println("Q: " + q);
      System.out.println("A: " + assistant.chat(q));
    }

    // Hold the JVM alive so the profiler flushes at least two recording cycles.
    System.out.println("Waiting 25 s for profiling data to flush...");
    try {
      Thread.sleep(25_000);
    } catch (InterruptedException ignored) {
    }
    System.out.println("Done.");
  }
}
