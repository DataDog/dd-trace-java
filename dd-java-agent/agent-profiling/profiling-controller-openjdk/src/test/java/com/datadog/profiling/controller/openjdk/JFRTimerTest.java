package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.controller.openjdk.events.QueueTimeEvent;
import com.datadog.profiling.utils.ExcludedVersions;
import datadog.trace.api.profiling.Timer;
import datadog.trace.api.profiling.Timing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JFRTimerTest {

  private Recording recording;
  Instant start;

  @BeforeEach
  public void setupRecording() {
    Assumptions.assumeFalse(ExcludedVersions.isVersionExcluded());
    start = Instant.now();
    recording = new Recording();
    recording.enable("datadog.QueueTime");
    recording.setSettings(Collections.singletonMap("datadog.QueueTime#threshold", "0 ms"));
    recording.start();
  }

  @Test
  public void testTimer() throws IOException {
    Assumptions.assumeFalse(ExcludedVersions.isVersionExcluded());
    JFRTimer timer = new JFRTimer();
    Timing timing = timer.start(Timer.TimerType.QUEUEING);
    assertTrue(timing instanceof QueueTimeEvent);
    QueueTimeEvent queueTimeEvent = (QueueTimeEvent) timing;
    queueTimeEvent.setQueue(ArrayBlockingQueue.class);
    queueTimeEvent.setScheduler(ThreadPoolExecutor.class);
    queueTimeEvent.setTask(FutureTask.class);
    Thread thread = new Thread(queueTimeEvent::close);
    thread.setName("something completely different");
    thread.start();
    Path output = Files.createTempFile("recording", ".jfr");
    output.toFile().deleteOnExit();
    recording.dump(output);
    recording.close();
    RecordedEvent event =
        RecordingFile.readAllEvents(output).stream()
            .filter(it -> it.getEventType().getName().equalsIgnoreCase("datadog.QueueTime"))
            .findFirst()
            .orElseThrow(AssertionError::new);
    assertEquals(FutureTask.class.getName(), event.getClass("task").getName());
    assertEquals(ThreadPoolExecutor.class.getName(), event.getClass("scheduler").getName());
    assertEquals(ArrayBlockingQueue.class.getName(), event.getClass("queue").getName());
    assertEquals(Thread.currentThread().getName(), event.getThread("origin").getJavaName());
    assertEquals(thread.getName(), event.getThread().getJavaName());
  }
}
