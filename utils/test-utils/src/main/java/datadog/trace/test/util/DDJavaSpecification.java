package datadog.trace.test.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.EnvironmentVariables;
import datadog.trace.junit.utils.config.WithConfigExtension;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
@SuppressForbidden
public class DDJavaSpecification {

  private static final long CHECK_TIMEOUT_MS = 3000;

  static final String CONTEXT_BINDER = "datadog.context.ContextBinder";
  static final String CONTEXT_MANAGER = "datadog.context.ContextManager";

  private static Boolean contextTestingAllowed;

  protected boolean assertThreadsEachCleanup = true;
  private static volatile boolean ignoreThreadCleanup;

  @BeforeAll
  static void beforeAll() {
    allowContextTesting();
    assertTrue(
        EnvironmentVariables.getAll().entrySet().stream()
            .noneMatch(e -> e.getKey().startsWith("DD_")));
    assertTrue(
        systemPropertiesExceptAllowed().entrySet().stream()
            .noneMatch(e -> e.getKey().toString().startsWith("dd.")));
    assertTrue(
        contextTestingAllowed,
        "Context not ready for testing. Ensure all test classes extend DDJavaSpecification");

    if (getDDThreads().isEmpty()) {
      ignoreThreadCleanup = false;
    } else {
      System.out.println(
          "Found DD threads before test started. Ignoring thread cleanup for this test class");
      ignoreThreadCleanup = true;
    }
  }

  static void allowContextTesting() {
    if (contextTestingAllowed == null) {
      try {
        Class<?> binderClass = Class.forName(CONTEXT_BINDER);
        Method binderAllowTesting = binderClass.getDeclaredMethod("allowTesting");
        binderAllowTesting.setAccessible(true);
        Class<?> managerClass = Class.forName(CONTEXT_MANAGER);
        Method managerAllowTesting = managerClass.getDeclaredMethod("allowTesting");
        managerAllowTesting.setAccessible(true);
        contextTestingAllowed =
            (Boolean) binderAllowTesting.invoke(null) && (Boolean) managerAllowTesting.invoke(null);
      } catch (ClassNotFoundException e) {
        // don't block testing if these types aren't found (project doesn't use context API)
        contextTestingAllowed =
            CONTEXT_BINDER.equals(e.getMessage()) || CONTEXT_MANAGER.equals(e.getMessage());
      } catch (Throwable ignore) {
        contextTestingAllowed = false;
      }
    }
  }

  @AfterAll
  static void afterAll() {
    assertTrue(
        EnvironmentVariables.getAll().entrySet().stream()
            .noneMatch(e -> e.getKey().startsWith("DD_")));
    assertTrue(
        systemPropertiesExceptAllowed().entrySet().stream()
            .noneMatch(e -> e.getKey().toString().startsWith("dd.")));

    checkThreads();
  }

  private static Map<Object, Object> systemPropertiesExceptAllowed() {
    List<String> allowlist =
        Arrays.asList(
            "dd.appsec.enabled", "dd.iast.enabled", "dd.integration.grizzly-filterchain.enabled");
    return System.getProperties().entrySet().stream()
        .filter(e -> !allowlist.contains(String.valueOf(e.getKey())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
