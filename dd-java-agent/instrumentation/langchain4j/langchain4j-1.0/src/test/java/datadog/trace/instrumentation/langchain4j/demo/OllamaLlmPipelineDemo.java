package datadog.trace.instrumentation.langchain4j.demo;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;

import java.time.Duration;

/**
 * Generates a JFR recording with llm.agent.phase tags against a local Ollama server.
 *
 * Run:
 *   java -javaagent:dd-java-agent.jar
 *     -Ddd.profiling.enabled=true
 *     -Ddd.profiling.context.attributes.llm.phase.enabled=true
 *     -cp <classpath>
 *     datadog.trace.instrumentation.langchain4j.demo.OllamaLlmPipelineDemo
 *
 * Prerequisites: ollama serve && ollama pull llama3
 */
public class OllamaLlmPipelineDemo {

    interface Assistant {
        String chat(String message);
    }

    public static void main(String[] args) {
        OllamaChatModel model = OllamaChatModel.builder()
            .baseUrl("http://localhost:11434")
            .modelName("llama3")
            .timeout(Duration.ofMinutes(2))
            .build();

        Assistant assistant = AiServices.builder(Assistant.class)
            .chatModel(model)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
            .build();

        System.out.println("Sending request...");
        String answer = assistant.chat("Explain Java garbage collection in one sentence.");
        System.out.println("Response: " + answer);
    }
}
