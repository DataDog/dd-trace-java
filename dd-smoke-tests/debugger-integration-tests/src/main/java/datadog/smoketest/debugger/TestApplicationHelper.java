package datadog.smoketest.debugger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

public class TestApplicationHelper {
  // instrumentation is done by main thread
  private static final String INSTRUMENTATION_DONE_MAIN_THREAD =
      "[main] DEBUG com.datadog.debugger.agent.DebuggerTransformer - Generating bytecode for class: %s";
  private static final String INSTRUMENTATION_DONE_BCKG_THREAD =
      "[dd-remote-config] DEBUG com.datadog.debugger.agent.DebuggerTransformer - Generating bytecode for class: %s";
  private static final String INSTRUMENTATION_DONE_TASK_THREAD =
      "[dd-task-scheduler] DEBUG com.datadog.debugger.agent.DebuggerTransformer - Generating bytecode for class: %s";
  private static final String RENTRANSFORMATION_CLASS =
      "[dd-remote-config] DEBUG com.datadog.debugger.agent.ConfigurationUpdater - Re-transforming class: %s";
  private static final String RETRANSFORMATION_DONE =
      "com.datadog.debugger.agent.ConfigurationUpdater - Re-transformation done";
  private static final String EXCEPTION_FINGERPRINT_ADDED =
      "DEBUG com.datadog.debugger.exception.AbstractExceptionDebugger - Exception Fingerprint ";
  private static final long SLEEP_MS = 100;
  private static final long TIMEOUT_S = 10;

  public static void waitForTransformerInstalled(String logFileName) throws IOException {
    waitForSpecificLogLine(
        Paths.get(logFileName),
        line ->
            line.contains(
                "DEBUG com.datadog.debugger.agent.ConfigurationUpdater - New transformer installed"),
        () -> {},
        Duration.ofMillis(SLEEP_MS),
        Duration.ofSeconds(TIMEOUT_S));
  }

  public static void waitForInstrumentation(String logFileName, String className)
      throws IOException {
    waitForInstrumentation(logFileName, className, null);
  }

  public static String waitForInstrumentation(String logFileName, String className, String fromLine)
      throws IOException {
    AtomicBoolean generatingByteCode = new AtomicBoolean();
    return waitForSpecificLogLine(
        Paths.get(logFileName),
        fromLine != null ? line -> line.contains(fromLine) : null,
        line -> {
          // when instrumentation is done by main thread, we are good to go
          if (line.contains(String.format(INSTRUMENTATION_DONE_MAIN_THREAD, className))) {
            return true;
          }
          if (!generatingByteCode.get()) {
            // instrumentation is done by background thread, need to wait for end of
            // re-transformation
            if (line.contains(String.format(INSTRUMENTATION_DONE_BCKG_THREAD, className))) {
              generatingByteCode.set(true);
            }
            if (line.contains(String.format(INSTRUMENTATION_DONE_TASK_THREAD, className))) {
              generatingByteCode.set(true);
            }
          } else {
            return line.contains(RETRANSFORMATION_DONE);
          }
          return false;
        },
        () -> {},
        Duration.ofMillis(SLEEP_MS),
        Duration.ofSeconds(TIMEOUT_S));
  }

  public static String waitForReTransformation(
      String logFileName, String className, String fromLine) throws IOException {
    AtomicBoolean retransforming = new AtomicBoolean();
    return waitForSpecificLogLine(
        Paths.get(logFileName),
        line -> line.contains(fromLine),
        line -> {
          if (!retransforming.get()) {
            retransforming.set(line.contains(String.format(RENTRANSFORMATION_CLASS, className)));
            return false;
          }
          return line.contains(RETRANSFORMATION_DONE);
        },
        () -> {},
        Duration.ofMillis(SLEEP_MS),
        Duration.ofSeconds(TIMEOUT_S));
  }

  public static String waitForExceptionFingerprint(String logFileName) throws IOException {
    return waitForSpecificLine(logFileName, EXCEPTION_FINGERPRINT_ADDED, null);
  }

  public static String waitForSpecificLine(String logFileName, String specificLine, String fromLine)
      throws IOException {
    return waitForSpecificLogLine(
        Paths.get(logFileName),
        fromLine != null ? line -> line.contains(fromLine) : null,
        line -> line.contains(specificLine),
        () -> {},
        Duration.ofMillis(SLEEP_MS),
        Duration.ofSeconds(TIMEOUT_S));
  }

  public static void waitForUpload(String logFileName, int expectedUploads) throws IOException {
    if (expectedUploads == -1) {
      System.out.println("wait for " + TIMEOUT_S + "s");
      LockSupport.parkNanos(Duration.ofSeconds(TIMEOUT_S).toNanos());
      return;
    }
    System.out.println("waitForUpload #" + expectedUploads);
    AtomicInteger uploadCount = new AtomicInteger(0);
    waitForSpecificLogLine(
        Paths.get(logFileName),
        line -> {
          if (line.contains("DEBUG com.datadog.debugger.uploader.BatchUploader - Upload done")) {
            int currentUploads = uploadCount.incrementAndGet();
            return currentUploads == expectedUploads;
          }
          return false;
        },
        () -> uploadCount.set(0),
        Duration.ofMillis(SLEEP_MS),
        Duration.ofSeconds(TIMEOUT_S));
  }

  private static String waitForSpecificLogLine(
      Path logFilePath,
      Predicate<String> lineMatcher,
      Runnable init,
      Duration sleep,
      Duration timeout)
      throws IOException {
    return waitForSpecificLogLine(logFilePath, null, lineMatcher, init, sleep, timeout);
  }

  private static String waitForSpecificLogLine(
      Path logFilePath,
      Predicate<String> fromLineMatcher,
      Predicate<String> lineMatcher,
      Runnable init,
      Duration sleep,
      Duration timeout)
      throws IOException {
    AtomicBoolean result = new AtomicBoolean();
    AtomicReference<String> matchedLine = new AtomicReference<>();
    long total = sleep.toNanos() == 0 ? 0 : timeout.toNanos() / sleep.toNanos();
    int i = 0;
    while (i < total && !result.get()) {
      System.out.flush();
      System.err.flush();
      init.run();
      AtomicBoolean fromLineMatched = new AtomicBoolean(fromLineMatcher == null);
      Files.lines(logFilePath)
          .forEach(
              line -> {
                if (!fromLineMatched.get()) {
                  fromLineMatched.set(fromLineMatcher.test(line));
                } else if (lineMatcher.test(line)) {
                  matchedLine.set(line);
                  result.set(true);
                }
              });
      LockSupport.parkNanos(sleep.toNanos());
      i++;
    }
    if (!result.get()) {
      throw new IllegalStateException("waitForSpecificLogLine timed out!");
    }
    return matchedLine.get();
  }
}
