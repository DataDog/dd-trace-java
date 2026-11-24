package datadog.trace.agent.test.scopediag;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DiagnosticEventBufferTest {
  @Test
  void reservationAfterEmptyCheckPreventsClosingAdmission() {
    DiagnosticEventBuffer buffer = new DiagnosticEventBuffer();
    assertFalse(buffer.hasPending());
    // A callback enters after the consumer observed quiescence, before it closes admission.
    DiagnosticEventBuffer.Event open = buffer.reserve();
    assertFalse(buffer.closeIfEmpty());
    AtomicInteger consumed = new AtomicInteger();
    open.publish(consumed::incrementAndGet);
    buffer.drain(false, 0, false);
    DiagnosticEventBuffer.Event close = buffer.reserve();
    close.publish(consumed::incrementAndGet);
    buffer.drain(false, 0, false);
    assertEquals(2, consumed.get());
    assertTrue(buffer.closeIfEmpty());
    assertNull(buffer.reserve());
  }

  @Test
  void closingEmptyBufferRefusesNewLifecycles() {
    DiagnosticEventBuffer buffer = new DiagnosticEventBuffer();
    assertTrue(buffer.closeIfEmpty());
    assertNull(buffer.reserve());
    assertFalse(buffer.hasPending());
  }

  @Test
  void stackCaptureCompletionCannotReorderReservations() {
    DiagnosticEventBuffer buffer = new DiagnosticEventBuffer();
    List<String> consumed = new ArrayList<>();
    DiagnosticEventBuffer.Event finish = buffer.reserve();
    DiagnosticEventBuffer.Event release = buffer.reserve();
    release.publish(() -> consumed.add("release"));
    buffer.drain(false, 0, false);
    assertTrue(consumed.isEmpty());
    finish.publish(() -> consumed.add("finish"));
    buffer.drain(false, 0, false);
    assertEquals(Arrays.asList("finish", "release"), consumed);
    assertFalse(buffer.hasPending());
  }

  @Test
  void closeRetainsAdmittedEventsAndRefusesLateProducers() {
    DiagnosticEventBuffer buffer = new DiagnosticEventBuffer();
    AtomicInteger consumed = new AtomicInteger();
    DiagnosticEventBuffer.Event event = buffer.reserve();
    buffer.close();
    assertNull(buffer.reserve());
    event.publish(consumed::incrementAndGet);
    buffer.drain(false, 0, true);
    buffer.close();
    assertNull(buffer.reserve());
    assertEquals(1, consumed.get());
    assertFalse(buffer.hasPending());
  }

  @Test
  void drainDoesNotChaseNewEvents() {
    DiagnosticEventBuffer buffer = new DiagnosticEventBuffer();
    AtomicInteger consumed = new AtomicInteger();
    buffer
        .reserve()
        .publish(() -> {
          consumed.incrementAndGet();
          buffer.reserve().publish(consumed::incrementAndGet);
        });
    buffer.drain(false, 0, false);
    assertEquals(1, consumed.get());
    assertTrue(buffer.hasPending());
    buffer.drain(false, 0, false);
    assertEquals(2, consumed.get());
  }

  @Test
  void timedOutDrainPreservesBacklog() {
    DiagnosticEventBuffer buffer = new DiagnosticEventBuffer();
    AtomicInteger consumed = new AtomicInteger();
    buffer.reserve().publish(consumed::incrementAndGet);
    buffer.drain(true, System.nanoTime(), false);
    assertEquals(0, consumed.get());
    assertTrue(buffer.hasPending());
    buffer.drain(false, 0, false);
    assertEquals(1, consumed.get());
  }

  @Test
  void failedProducerDoesNotStrandLaterEvents() {
    DiagnosticEventBuffer buffer = new DiagnosticEventBuffer();
    AtomicInteger consumed = new AtomicInteger();
    DiagnosticEventBuffer.Event failed = buffer.reserve();
    buffer.reserve().publish(consumed::incrementAndGet);
    failed.publishIfAbsent();
    buffer.drain(false, 0, true);
    assertEquals(1, consumed.get());
    assertFalse(buffer.hasPending());
  }

  @Test
  void concurrentProducersRacingCloseLoseNoReservations() throws Exception {
    DiagnosticEventBuffer buffer = new DiagnosticEventBuffer();
    AtomicInteger admitted = new AtomicInteger();
    AtomicInteger consumed = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(4);
    List<FutureTask<Void>> producers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      FutureTask<Void> producer =
          new FutureTask<>(() -> {
            for (int n = 0; n < 1000; n++) {
              DiagnosticEventBuffer.Event event = buffer.reserve();
              if (event == null) {
                break;
              }
              admitted.incrementAndGet();
              if (n == 0) {
                started.countDown();
              }
              event.publish(consumed::incrementAndGet);
            }
            return null;
          });
      producers.add(producer);
      Thread thread = new Thread(producer, "diagnostic-producer-" + i);
      thread.setDaemon(true);
      thread.start();
    }
    try {
      assertTrue(started.await(5, SECONDS));
    } finally {
      buffer.close();
    }
    for (FutureTask<Void> producer : producers) {
      producer.get(5, SECONDS);
    }
    buffer.drain(false, 0, true);
    assertEquals(admitted.get(), consumed.get());
    assertFalse(buffer.hasPending());
  }
}
