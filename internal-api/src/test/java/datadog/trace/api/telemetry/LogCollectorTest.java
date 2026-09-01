package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LogCollectorTest {

  @Test
  void setsTracerTime() {
    LogCollector logCollector = new LogCollector(1);
    long before = System.currentTimeMillis() / 1000;

    logCollector.addLogMessage("ERROR", "Message 1", null);

    long after = System.currentTimeMillis() / 1000;
    LogCollector.RawLogMessage log = onlyLog(logCollector.drain());
    assertTrue(log.timestamp >= before);
    assertTrue(log.timestamp <= after);
  }

  @Test
  void limitsLogMessages() {
    LogCollector logCollector = new LogCollector(3);

    logCollector.addLogMessage("ERROR", "Message 1", null);
    logCollector.addLogMessage("ERROR", "Message 2", null);
    logCollector.addLogMessage("ERROR", "Message 3", null);
    logCollector.addLogMessage("ERROR", "Message 4", null);

    assertEquals(3, logCollector.drain().size());
  }

  @Test
  void groupsMessages() {
    LogCollector logCollector = new LogCollector(10);

    logCollector.addLogMessage("ERROR", "First Message", null);
    logCollector.addLogMessage("ERROR", "Second Message", null);
    logCollector.addLogMessage("ERROR", "Third Message", null);
    logCollector.addLogMessage("ERROR", "Fourth Message", null);
    logCollector.addLogMessage("ERROR", "Second Message", null);
    logCollector.addLogMessage("ERROR", "Third Message", null);
    logCollector.addLogMessage("ERROR", "Fourth Message", null);
    logCollector.addLogMessage("ERROR", "Third Message", null);
    logCollector.addLogMessage("ERROR", "Fourth Message", null);
    logCollector.addLogMessage("ERROR", "Fourth Message", null);

    Collection<LogCollector.RawLogMessage> logs = logCollector.drain();
    assertEquals(4, logs.size());
    assertLog(logs, "First Message", 1);
    assertLog(logs, "Second Message", 2);
    assertLog(logs, "Third Message", 3);
    assertLog(logs, "Fourth Message", 4);
  }

  @Test
  void dropsDuplicatesWhenFull() {
    LogCollector logCollector = new LogCollector(1);

    logCollector.addLogMessage("ERROR", "Message", null);
    logCollector.addLogMessage("ERROR", "Message", null);

    assertEquals(1, onlyLog(logCollector.drain()).count);
  }

  @Test
  void reusesCapacityAfterDrain() {
    LogCollector logCollector = new LogCollector(1);

    logCollector.addLogMessage("ERROR", "First", null);
    assertEquals("First", onlyLog(logCollector.drain()).message);
    logCollector.addLogMessage("ERROR", "Second", null);

    assertEquals("Second", onlyLog(logCollector.drain()).message);
    assertTrue(logCollector.drain().isEmpty());
  }

  @Test
  void groupsEquivalentThrowablesAndKeepsFirstMetadata() {
    LogCollector logCollector = new LogCollector(2);
    Throwable first = throwableAtLine(10);
    Throwable second = throwableAtLine(10);

    logCollector.addLogMessage("ERROR", "Message", first, "source:first");
    logCollector.addLogMessage("ERROR", "Message", second, "source:second");

    LogCollector.RawLogMessage log = onlyLog(logCollector.drain());
    assertEquals(2, log.count);
    assertSame(first, log.throwable);
    assertEquals("source:first", log.tags);
  }

  @Test
  void keepsDifferentStackTracesSeparate() {
    LogCollector logCollector = new LogCollector(2);

    logCollector.addLogMessage("ERROR", "Message", throwableAtLine(10));
    logCollector.addLogMessage("ERROR", "Message", throwableAtLine(20));

    assertEquals(2, logCollector.drain().size());
  }

  @Test
  void rawLogMessageEqualityMatchesDeduplication() {
    LogCollector.RawLogMessage first =
        new LogCollector.RawLogMessage("ERROR", "Message", throwableAtLine(10), "first", 1);
    LogCollector.RawLogMessage equivalent =
        new LogCollector.RawLogMessage("ERROR", "Message", throwableAtLine(10), "second", 2);
    LogCollector.RawLogMessage different =
        new LogCollector.RawLogMessage("ERROR", "Message", throwableAtLine(20), "first", 1);

    assertEquals(first, equivalent);
    assertEquals(first.hashCode(), equivalent.hashCode());
    assertNotEquals(first, different);
  }

  @Test
  void countsConcurrentDuplicates() throws Exception {
    int threadCount = 16;
    int messagesPerThread = 1_000;
    LogCollector logCollector = new LogCollector(2);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    Future<?>[] futures = new Future<?>[threadCount];
    try {
      for (int i = 0; i < threadCount; i++) {
        futures[i] =
            executor.submit(
                () -> {
                  start.await();
                  for (int message = 0; message < messagesPerThread; message++) {
                    logCollector.addLogMessage("ERROR", "Message", null);
                  }
                  return null;
                });
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    assertEquals(threadCount * messagesPerThread, onlyLog(logCollector.drain()).count);
  }

  @Test
  void capsConcurrentDistinctMessages() throws Exception {
    int capacity = 3;
    int threadCount = 16;
    LogCollector logCollector = new LogCollector(capacity);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    Future<?>[] futures = new Future<?>[threadCount];
    try {
      for (int i = 0; i < threadCount; i++) {
        String message = "Message " + i;
        futures[i] =
            executor.submit(
                () -> {
                  start.await();
                  logCollector.addLogMessage("ERROR", message, null);
                  return null;
                });
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    assertEquals(capacity, logCollector.drain().size());
  }

  private static Throwable throwableAtLine(int lineNumber) {
    Throwable throwable = new IllegalStateException("ignored by deduplication");
    throwable.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("Example", "run", "Example.java", lineNumber)
        });
    return throwable;
  }

  private static LogCollector.RawLogMessage onlyLog(Collection<LogCollector.RawLogMessage> logs) {
    assertEquals(1, logs.size());
    return logs.iterator().next();
  }

  private static void assertLog(
      Collection<LogCollector.RawLogMessage> logs, String message, int count) {
    LogCollector.RawLogMessage log =
        logs.stream()
            .filter(candidate -> message.equals(candidate.message))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing log message: " + message));
    assertEquals("ERROR", log.logLevel);
    assertEquals(count, log.count);
  }
}
