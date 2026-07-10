package datadog.trace.test.util;

import datadog.trace.junit.utils.config.WithConfigExtension;
import datadog.trace.junit.utils.context.AllowContextTestingExtension;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({
  CleanConfigStateExtension.class,
  WithConfigExtension.class,
  AllowContextTestingExtension.class
})
@SuppressForbidden
public class DDJavaSpecification {

  private static final long CHECK_TIMEOUT_MS = 3000;

  protected boolean assertThreadsEachCleanup = true;
  private static volatile boolean ignoreThreadCleanup;

  @BeforeAll
  static void beforeAll() {
    if (getDDThreads().isEmpty()) {
      ignoreThreadCleanup = false;
    } else {
      System.out.println(
          "Found DD threads before test started. Ignoring thread cleanup for this test class");
      ignoreThreadCleanup = true;
    }
  }

  @AfterAll
  static void afterAll() {
    checkThreads();
  }

  @AfterEach
  void cleanup() {
    if (assertThreadsEachCleanup) {
      checkThreads();
    }
  }

  static Set<Thread> getDDThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(
            t ->
                t.getName().startsWith("dd-")
                    && !t.getName().equals("dd-task-scheduler")
                    && !t.getName().equals("dd-cassandra-session-executor"))
        .collect(Collectors.toSet());
  }

  static void checkThreads() {
    if (ignoreThreadCleanup) {
      return;
    }

    long deadline = System.currentTimeMillis() + CHECK_TIMEOUT_MS;

    Set<Thread> threads = getDDThreads();
    while (System.currentTimeMillis() < deadline && !threads.isEmpty()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      threads = getDDThreads();
    }

    if (!threads.isEmpty()) {
      System.out.println("WARNING: DD threads still active. Forget to close() a tracer?");
      List<String> names = threads.stream().map(Thread::getName).collect(Collectors.toList());
      System.out.println(names);
    }
  }
}
