package datadog.trace.instrumentation.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.jfr.llm.AiServiceEvent;
import datadog.trace.bootstrap.instrumentation.jfr.llm.ChatModelEvent;
import datadog.trace.bootstrap.instrumentation.jfr.llm.LLMOperation;
import datadog.trace.bootstrap.instrumentation.jfr.llm.ToolExecutorEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmJfrEventTest {

  @TempDir Path tempDir;

  @Test
  void allEventClassesAreAnnotatedWithLLMOperation() {
    assertTrue(ChatModelEvent.class.isAnnotationPresent(LLMOperation.class));
    assertTrue(ToolExecutorEvent.class.isAnnotationPresent(LLMOperation.class));
    assertTrue(AiServiceEvent.class.isAnnotationPresent(LLMOperation.class));
  }

  @Test
  void chatModelEventIsEmittedToJfr() throws Exception {
    Path dump = tempDir.resolve("jfr-chat.jfr");
    try (Recording r = new Recording()) {
      r.enable("datadog.ChatModel");
      r.start();
      ChatModelEvent event = new ChatModelEvent("gpt-4");
      event.end();
      assertTrue(event.shouldCommit(), "event should be committable");
      if (event.shouldCommit()) event.commit();
      r.stop();
      r.dump(dump);
    }
    List<RecordedEvent> events = readEvents(dump, "datadog.ChatModel");
    assertEquals(1, events.size());
    assertEquals("gpt-4", events.get(0).getString("modelId"));
  }

  @Test
  void toolExecutorEventIsEmittedToJfr() throws Exception {
    Path dump = tempDir.resolve("jfr-tool.jfr");
    try (Recording r = new Recording()) {
      r.enable("datadog.ToolExecutor");
      r.start();
      ToolExecutorEvent event = new ToolExecutorEvent("getWeather");
      event.end();
      assertTrue(event.shouldCommit(), "event should be committable");
      if (event.shouldCommit()) event.commit();
      r.stop();
      r.dump(dump);
    }
    List<RecordedEvent> events = readEvents(dump, "datadog.ToolExecutor");
    assertEquals(1, events.size());
    assertEquals("getWeather", events.get(0).getString("toolName"));
  }

  @Test
  void aiServiceEventIsEmittedToJfr() throws Exception {
    Path dump = tempDir.resolve("jfr-aiservice.jfr");
    try (Recording r = new Recording()) {
      r.enable("datadog.AiService");
      r.start();
      AiServiceEvent event = new AiServiceEvent("WeatherAssistant", "chat");
      event.end();
      assertTrue(event.shouldCommit(), "event should be committable");
      if (event.shouldCommit()) event.commit();
      r.stop();
      r.dump(dump);
    }
    List<RecordedEvent> events = readEvents(dump, "datadog.AiService");
    assertEquals(1, events.size());
    assertEquals("WeatherAssistant", events.get(0).getString("serviceType"));
    assertEquals("chat", events.get(0).getString("methodName"));
  }

  private static List<RecordedEvent> readEvents(Path path, String eventType) throws Exception {
    try (RecordingFile rf = new RecordingFile(path)) {
      List<RecordedEvent> all = new java.util.ArrayList<>();
      while (rf.hasMoreEvents()) all.add(rf.readEvent());
      return all.stream()
          .filter(e -> e.getEventType().getName().equals(eventType))
          .collect(Collectors.toList());
    }
  }
}
