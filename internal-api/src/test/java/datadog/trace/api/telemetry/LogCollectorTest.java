package datadog.trace.api.telemetry;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import datadog.trace.test.util.PollingConditions;
import datadog.trace.util.ConcurrentHashtable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class LogCollectorTest {
  private static final long TIMEOUT_SECONDS = 10;

  @Test
  void setsTracerTime() {
    // Given
    LogCollector logCollector = new LogCollector(1);
    long before = System.currentTimeMillis() / 1000;

    // When
    logCollector.addLogMessage("ERROR", "Message 1", null);

    long after = System.currentTimeMillis() / 1000;
    LogCollector.RawLogMessage log = singleLog(logCollector.drain());

    // Then
    assertThat(log.timestamp).isBetween(before, after);
  }

  @Test
  void limitsLogMessages() {
    // Given
    LogCollector logCollector = new LogCollector(3);

    // When
    logCollector.addLogMessage("ERROR", "Message 1", null);
    logCollector.addLogMessage("ERROR", "Message 2", null);
    logCollector.addLogMessage("ERROR", "Message 3", null);
    logCollector.addLogMessage("ERROR", "Message 4", null);

    Collection<LogCollector.RawLogMessage> logs = logCollector.drain();

    // Then
    assertThat(logs).hasSize(3);
    assertContainsLog(logs, "Message 1", 1);
    assertContainsLog(logs, "Message 2", 1);
    assertContainsLog(logs, "Message 3", 1);
    assertThat(logs).extracting(log -> log.message).doesNotContain("Message 4");
  }

  @Test
  void groupsMessages() {
    // Given
    LogCollector logCollector = new LogCollector(10);

    // When
    logCollector.addLogMessage("ERROR", "Foo Message", null);
    logCollector.addLogMessage("ERROR", "Bar Message", null);
    logCollector.addLogMessage("ERROR", "Baz Message", null);
    logCollector.addLogMessage("ERROR", "Qux Message", null);
    logCollector.addLogMessage("ERROR", "Bar Message", null);
    logCollector.addLogMessage("ERROR", "Baz Message", null);
    logCollector.addLogMessage("ERROR", "Qux Message", null);
    logCollector.addLogMessage("ERROR", "Baz Message", null);
    logCollector.addLogMessage("ERROR", "Qux Message", null);
    logCollector.addLogMessage("ERROR", "Qux Message", null);

    Collection<LogCollector.RawLogMessage> logs = logCollector.drain();

    // Then
    assertThat(logs).hasSize(4);
    assertContainsLog(logs, "Foo Message", 1);
    assertContainsLog(logs, "Bar Message", 2);
    assertContainsLog(logs, "Baz Message", 3);
    assertContainsLog(logs, "Qux Message", 4);
  }

  @Test
  void dropsDuplicatesWhenFull() {
    // Given
    LogCollector logCollector = new LogCollector(1);
    logCollector.addLogMessage("ERROR", "Message", null);

    // When
    logCollector.addLogMessage("ERROR", "Message", null);

    // Then
    assertThat(singleLog(logCollector.drain()).count).isEqualTo(1);
  }

  @Test
  void reusesCapacityAfterDrain() {
    // Given
    LogCollector logCollector = new LogCollector(1);

    // When
    logCollector.addLogMessage("ERROR", "First", null);

    // Then
    assertThat(singleLog(logCollector.drain()).message).isEqualTo("First");

    // When
    logCollector.addLogMessage("ERROR", "Second", null);

    // Then
    assertThat(singleLog(logCollector.drain()).message).isEqualTo("Second");
    assertThat(logCollector.drain()).isEmpty();
  }

  @Test
  void acceptsMessageAfterDrainDetachesFullBucket() throws Exception {
    // Given
    LogCollector logCollector = new LogCollector(1);
    logCollector.addLogMessage("ERROR", "First", null);
    ConcurrentHashtable.State<LogCollector.RawLogMessage> state = logCollector.rawLogMessages;
    CountDownLatch bucketDetached = new CountDownLatch(1);
    CountDownLatch releaseDrain = new CountDownLatch(1);
    FutureTask<Collection<LogCollector.RawLogMessage>> drainTask =
        new FutureTask<>(
            () -> {
              List<LogCollector.RawLogMessage> drained = new ArrayList<>();
              ConcurrentHashtable.drain(
                  state,
                  drained,
                  (logs, log) -> {
                    bucketDetached.countDown();
                    await(releaseDrain);
                    logs.add(log);
                  });
              return drained;
            });
    Thread drainThread = new Thread(drainTask, "log-collector-drain");
    FutureTask<Void> writerTask =
        new FutureTask<>(
            () -> {
              logCollector.addLogMessage("ERROR", "Second", null);
              return null;
            });
    Thread writerThread = new Thread(writerTask, "log-collector-writer");

    // When
    try {
      drainThread.start();
      await(bucketDetached);
      writerThread.start();
      new PollingConditions(TIMEOUT_SECONDS)
          .eventually(() -> assertThat(writerThread.getState()).isEqualTo(Thread.State.BLOCKED));
    } finally {
      releaseDrain.countDown();
    }

    // Then
    Collection<LogCollector.RawLogMessage> firstDrain = await(drainTask);
    await(writerTask);
    assertThat(singleLog(firstDrain).message).isEqualTo("First");
    assertThat(singleLog(logCollector.drain()).message).isEqualTo("Second");
  }

  @Test
  void groupsEquivalentThrowablesAndKeepsFirstMetadata() {
    // Given
    LogCollector logCollector = new LogCollector(2);
    Throwable first = throwableWithMethod("run", 10);
    Throwable second = throwableWithMethod("run", 10);

    // When
    logCollector.addLogMessage("ERROR", "Message", first, "source:first");
    logCollector.addLogMessage("ERROR", "Message", second, "source:second");

    LogCollector.RawLogMessage log = singleLog(logCollector.drain());

    // Then
    assertThat(log.count).isEqualTo(2);
    assertThat(log.throwable).isSameAs(first);
    assertThat(log.tags).isEqualTo("source:first");
  }

  @Test
  void keepsDifferentStackTracesSeparate() {
    // Given
    LogCollector logCollector = new LogCollector(2);

    // When
    logCollector.addLogMessage("ERROR", "Message", throwableWithMethod("run", 10));
    logCollector.addLogMessage("ERROR", "Message", throwableWithMethod("run", 20));

    // Then
    assertThat(logCollector.drain()).hasSize(2);
  }

  @Test
  void rawLogMessageEqualityMatchesDeduplication() {
    // Given
    LogCollector.RawLogMessage first =
        new LogCollector.RawLogMessage("ERROR", "Message", throwableWithMethod("run", 10), "first", 1);
    LogCollector.RawLogMessage equivalent =
        new LogCollector.RawLogMessage("ERROR", "Message", throwableWithMethod("run", 10), "second", 2);
    LogCollector.RawLogMessage different =
        new LogCollector.RawLogMessage("ERROR", "Message", throwableWithMethod("runIt", 20), "first", 1);

    // Then
    assertThat(first).isEqualTo(equivalent);
    assertThat(first).hasSameHashCodeAs(equivalent);
    assertThat(first).isNotEqualTo(different);
  }

  @Test
  void countsConcurrentDuplicates() throws Exception {
    // Given
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
                  await(start);
                  for (int message = 0; message < messagesPerThread; message++) {
                    logCollector.addLogMessage("ERROR", "Message", null);
                  }
                  return null;
                });
      }

      // When
      release(start);
      await(futures);
    } finally {
      shutdown(executor);
    }

    // Then
    assertThat(singleLog(logCollector.drain()).count).isEqualTo(threadCount * messagesPerThread);
  }

  @Test
  void groupsConcurrentEquivalentThrowables() throws Exception {
    // Given
    int threadCount = 16;
    LogCollector logCollector = new LogCollector(2);
    Throwable first = throwableWithMethod("run", 10);
    logCollector.addLogMessage("ERROR", "Message", first);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    Future<?>[] futures = new Future<?>[threadCount];
    try {
      for (int i = 0; i < threadCount; i++) {
        futures[i] =
            executor.submit(
                () -> {
                  await(start);
                  logCollector.addLogMessage("ERROR", "Message", throwableWithMethod("run", 10));
                  return null;
                });
      }

      // When
      release(start);
      await(futures);
    } finally {
      shutdown(executor);
    }

    LogCollector.RawLogMessage log = singleLog(logCollector.drain());

    // Then
    assertThat(log.count).isEqualTo(threadCount + 1);
    assertThat(log.throwable).isSameAs(first);
  }

  @Test
  void capsConcurrentDistinctMessages() throws Exception {
    // Given
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
                  await(start);
                  logCollector.addLogMessage("ERROR", message, null);
                  return null;
                });
      }

      // When
      release(start);
      await(futures);
    } finally {
      shutdown(executor);
    }

    // Then
    assertThat(logCollector.drain()).hasSize(capacity);
  }

  private static Throwable throwableWithMethod(String methodName, int lineNumber) {
    Throwable throwable = new IllegalStateException("ignored by deduplication");
    throwable.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("Example", methodName, "Example.java", lineNumber)
        });
    return throwable;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(TIMEOUT_SECONDS, SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void release(CountDownLatch latch) {
    latch.countDown();
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.get(TIMEOUT_SECONDS, SECONDS);
  }

  private static void await(Future<?>[] futures) throws Exception {
    for (Future<?> future : futures) {
      await(future);
    }
  }

  private static void shutdown(ExecutorService executor) throws InterruptedException {
    executor.shutdownNow();
    assertThat(executor.awaitTermination(TIMEOUT_SECONDS, SECONDS)).isTrue();
  }

  private static LogCollector.RawLogMessage singleLog(Collection<LogCollector.RawLogMessage> logs) {
    assertThat(logs).hasSize(1);
    return logs.iterator().next();
  }

  private static void assertContainsLog(
      Collection<LogCollector.RawLogMessage> logs, String message, int count) {
    assertThat(logs)
        .as("logs containing message %s", message)
        .filteredOn(candidate -> message.equals(candidate.message))
        .singleElement()
        .satisfies(
            log -> {
              assertThat(log.logLevel).isEqualTo("ERROR");
              assertThat(log.count).isEqualTo(count);
              assertThat(log.throwable).isNull();
            });
  }
}
