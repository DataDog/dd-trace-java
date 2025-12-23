import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

public class App {
  private static final String LOG_FILENAME = System.getenv().get("DD_LOG_FILE");

  public String getGreeting() {
    return "Hello World!";
  }

  public static void main(String[] args) throws Exception {
    System.out.println("Waiting for instrumentation...");
    waitForInstrumentation();
    System.out.println("Executing method getGreeting");
    System.out.println(new App().getGreeting());
    System.out.println("Executed");
    waitForSnapshotUpload(1);
    System.out.println("Exiting...");
  }

  private static void waitForInstrumentation() throws IOException {
    AtomicBoolean generatingByteCode = new AtomicBoolean();
    waitForSpecificLogLine(
      Paths.get(LOG_FILENAME),
      line -> {
        // when instrumentation is done by main thread, we are good to go
        if (line.contains(
          "[main] DEBUG com.datadog.debugger.agent.DebuggerTransformer - Generating bytecode for class: App")) {
          return true;
        }
        if (!generatingByteCode.get()) {
          // instrumentation is done by background thread, need to wait for end of
          // re-transformation
          if (line.contains(
            "[dd-task-scheduler] DEBUG com.datadog.debugger.agent.DebuggerTransformer - Generating bytecode for class: App")) {
            generatingByteCode.set(true);
          }
        } else {
          if (line.contains(
            "com.datadog.debugger.agent.DebuggerProbeRedefinition - Re-transformation done.")) {
            return true;
          }
        }
        return false;
      },
      () -> {},
      Duration.ofMillis(100),
      Duration.ofSeconds(10));
  }

  private static void waitForSnapshotUpload(int expectedUploads) throws IOException {
    AtomicInteger uploadCount = new AtomicInteger(0);
    waitForSpecificLogLine(
      Paths.get(LOG_FILENAME),
      line -> {
        if (line.contains("DEBUG com.datadog.debugger.uploader.SnapshotUploader - Upload done")) {
          int currentUploads = uploadCount.incrementAndGet();
          return currentUploads == expectedUploads;
        }
        return false;
      },
      () -> uploadCount.set(0),
      Duration.ofMillis(100),
      Duration.ofSeconds(10));
  }

  private static void waitForSpecificLogLine(
    Path logFilePath,
    Predicate<String> lineMatcher,
    Runnable init,
    Duration sleep,
    Duration timeout)
    throws IOException {
    boolean[] result = new boolean[] {false};
    long total = sleep.toNanos() == 0 ? 0 : timeout.toNanos() / sleep.toNanos();
    int i = 0;
    while (i < total && !result[0]) {
      System.out.flush();
      System.err.flush();
      init.run();
      Files.lines(logFilePath)
        .forEach(
          it -> {
            if (lineMatcher.test(it)) {
              result[0] = true;
            }
          });
      LockSupport.parkNanos(sleep.toNanos());
      i++;
    }
    if (!result[0]) {
      System.out.println("waitForSpecificLogLine timed out!");
    }
  }
}
