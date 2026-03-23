package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.moditect.jfrunit.JfrEventTest;

@JfrEventTest
@EnabledForJreRange(min = JRE.JAVA_11)
class AgentTracerSpanCreationMemoryTest {
  private static final String INSTRUMENTATION_NAME = "memory-test";
  private static final CharSequence SPAN_NAME = "span-creation";
  private static final CharSequence CHILD_SPAN_NAME = "span-creation-child";

  @Test
  void startSpanDoesNotAllocateInNoopMode() throws Exception {
    warmupSpanCreation();

    List<RecordedEvent> events = recordAllocationsWhileCreatingSpans();
    long allocationEventsOnStartSpan = events.stream().filter(this::isStartSpanAllocation).count();

    assertEquals(0L, allocationEventsOnStartSpan);
  }

  private static void warmupSpanCreation() {
    for (int i = 0; i < 10_000; i++) {
      createSpan();
    }
  }

  private static List<RecordedEvent> recordAllocationsWhileCreatingSpans() throws Exception {
    try (Recording recording = new Recording()) {
      recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace().withThreshold(Duration.ZERO);
      recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace().withThreshold(Duration.ZERO);
      recording.start();
      for (int i = 0; i < 25_000; i++) {
        createSpan();
      }
      recording.stop();
      return readEvents(recording);
    }
  }

  private static void createSpan() {
    AgentSpan span = AgentTracer.startSpan(INSTRUMENTATION_NAME, SPAN_NAME);
    try (AgentScope scope = AgentTracer.activateSpan(span)) {
      AgentSpan childSpan = AgentTracer.startSpan(INSTRUMENTATION_NAME, CHILD_SPAN_NAME);
      try (AgentScope childScope = AgentTracer.activateSpan(childSpan)) {
      } finally {
        childSpan.finish();
      }
    } finally {
      span.finish();
    }
  }

  private static List<RecordedEvent> readEvents(Recording recording) throws Exception {
    Path recordingPath = Files.createTempFile("agent-tracer-span-memory-test", ".jfr");
    try {
      recording.dump(recordingPath);
      List<RecordedEvent> events = new ArrayList<>();
      try (RecordingFile recordingFile = new RecordingFile(recordingPath)) {
        while (recordingFile.hasMoreEvents()) {
          events.add(recordingFile.readEvent());
        }
      }
      return events;
    } finally {
      Files.deleteIfExists(recordingPath);
    }
  }

  private boolean isStartSpanAllocation(RecordedEvent event) {
    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null) {
      return false;
    }

    for (RecordedFrame frame : stackTrace.getFrames()) {
      RecordedMethod method = frame.getMethod();
      if (method == null || method.getType() == null) {
        continue;
      }

      if ("datadog.trace.bootstrap.instrumentation.api.AgentTracer".equals(method.getType().getName())
          && "startSpan".equals(method.getName())) {
        return true;
      }
    }
    return false;
  }
}