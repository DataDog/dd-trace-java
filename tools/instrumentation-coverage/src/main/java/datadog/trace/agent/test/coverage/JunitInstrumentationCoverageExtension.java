package datadog.trace.agent.test.coverage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/** Observes Jupiter instrumentation-test bodies while preserving the harness's agent lifecycle. */
public final class JunitInstrumentationCoverageExtension
    implements InvocationInterceptor, AfterAllCallback {
  private static final String HARNESS = "datadog.trace.agent.test.AbstractInstrumentationTest";
  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(JunitInstrumentationCoverageExtension.class);

  @Override
  public void interceptBeforeAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> method,
      ExtensionContext context)
      throws Throwable {
    if (enabled(context) && isHarnessMethod(method, "initAll")) {
      if (context.getStore(NAMESPACE).get("session") != null) {
        throw new IllegalStateException("Coverage session already installed");
      }
      Session session = new Session(context.getRequiredTestClass());
      context.getStore(NAMESPACE).put("session", session);
      try {
        session.start();
        invocation.proceed();
      } catch (Throwable failure) {
        try {
          session.finish();
        } catch (Throwable cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    } else {
      invocation.proceed();
    }
  }

  @Override
  public void interceptTestMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> method,
      ExtensionContext context)
      throws Throwable {
    observe(invocation, context);
  }

  @Override
  public void interceptTestTemplateMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> method,
      ExtensionContext context)
      throws Throwable {
    observe(invocation, context);
  }

  @Override
  public void interceptDynamicTest(
      Invocation<Void> invocation,
      DynamicTestInvocationContext dynamicTest,
      ExtensionContext context)
      throws Throwable {
    observe(invocation, context);
  }

  private static void observe(Invocation<Void> invocation, ExtensionContext context)
      throws Throwable {
    if (!enabled(context)) {
      invocation.proceed();
      return;
    }
    Session session = context.getStore(NAMESPACE).get("session", Session.class);
    if (session == null) {
      throw new IllegalStateException("Instrumentation harness did not start the coverage session");
    }
    session.verifyAgent();
    // Unique IDs distinguish parameterized invocations even when their display names are equal.
    String scenarioId = "junit:" + context.getUniqueId();
    runObserved(session.coverage, scenarioId, context.getDisplayName(), invocation);
  }

  static void runObserved(ContextCoverage coverage, String scenario, Invocation<Void> invocation)
      throws Throwable {
    runObserved(coverage, scenario, scenario, invocation);
  }

  static void runObserved(
      ContextCoverage coverage, String scenarioId, String scenarioName, Invocation<Void> invocation)
      throws Throwable {
    Throwable[] failure = new Throwable[1];
    ContextCoverage.inScenario(
        scenarioId,
        scenarioName,
        () -> {
          coverage.resume(scenarioId, scenarioName);
          try {
            invocation.proceed();
          } catch (Throwable thrown) {
            failure[0] = thrown;
          } finally {
            coverage.pause();
          }
        });
    if (failure[0] != null) {
      throw failure[0];
    }
  }

  @Override
  public void interceptAfterAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> method,
      ExtensionContext context)
      throws Throwable {
    Throwable cleanupFailure = null;
    if (enabled(context) && isHarnessMethod(method, "tearDownAll")) {
      try {
        finish(context);
      } catch (Throwable failure) {
        cleanupFailure = failure;
      }
    }
    // Always let the harness remove its transformer and close its tracer, even if reporting fails.
    try {
      invocation.proceed();
    } catch (Throwable failure) {
      if (cleanupFailure != null) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
    if (cleanupFailure != null) {
      throw cleanupFailure;
    }
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    // Fallback for setup failure; normal teardown finishes before the agent is removed.
    finish(context);
  }

  private static void finish(ExtensionContext context) throws Exception {
    Session session = context.getStore(NAMESPACE).get("session", Session.class);
    if (session != null && session.testClass == context.getRequiredTestClass()) {
      session.finish();
    }
  }

  private static boolean isHarnessMethod(ReflectiveInvocationContext<Method> method, String name) {
    return method.getExecutable().getDeclaringClass().getName().equals(HARNESS)
        && method.getExecutable().getName().equals(name);
  }

  private static boolean enabled(ExtensionContext context) {
    if (System.getProperty("test.context.coverage.classes", "").trim().isEmpty()) {
      return false;
    }
    for (Class<?> type = context.getRequiredTestClass();
        type != null;
        type = type.getSuperclass()) {
      if (type.getName().equals(HARNESS)) {
        return true;
      }
    }
    return false;
  }

  private static Object field(Class<?> owner, Object instance, String name)
      throws ReflectiveOperationException {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }

  private static final class Session {
    private final Class<?> testClass;
    private ContextCoverage coverage;
    private Path output;
    private boolean finished;

    private Session(Class<?> testClass) {
      this.testClass = testClass;
    }

    private void start() throws Exception {
      String directory = System.getProperty("test.context.coverage.output", "").trim();
      if (directory.isEmpty()) {
        throw new IllegalArgumentException("test.context.coverage.output is required");
      }
      output =
          Paths.get(directory)
              .resolve("worker-" + System.getProperty("org.gradle.test.worker", "unknown"))
              .resolve(testClass.getName());
      ClassLoader loader = testClass.getClassLoader();
      String[] names = System.getProperty("test.context.coverage.classes").split(",", -1);
      Class<?>[] targets = new Class<?>[names.length];
      for (int i = 0; i < names.length; i++) {
        targets[i] = Class.forName(names[i].trim(), false, loader);
        if (targets[i].getClassLoader() != null && targets[i].getClassLoader() != loader) {
          throw new IllegalArgumentException(
              "Target must use bootstrap or the observer loader: " + names[i]);
        }
      }
      coverage = ContextCoverage.observe(targets);
      coverage.pause();
      coverage.metadata("mode", "instrumentation-test");
      coverage.metadata("adapter", "junit");
      coverage.metadata("specName", testClass.getName());
      coverage.metadata("runId", System.getProperty("test.context.coverage.run-id", ""));
      coverage.metadata("workerId", System.getProperty("org.gradle.test.worker", "unknown"));
      coverage.metadata("agentInstalled", false);
      coverage.metadata("agentTransformerInstalled", false);
      coverage.metadata(
          "scenarioAttribution",
          "stable Jupiter unique-id identity; initiating-thread attribution is exact and worker "
              + "attribution is temporal within one serialized test window");
    }

    private void verifyAgent() throws Exception {
      ClassLoader loader = testClass.getClassLoader();
      Class<?> harness = Class.forName(HARNESS, false, loader);
      Class<?> agentTracer =
          Class.forName("datadog.trace.bootstrap.instrumentation.api.AgentTracer", false, loader);
      boolean registered = Boolean.TRUE.equals(agentTracer.getMethod("isRegistered").invoke(null));
      boolean installed = field(harness, null, "activeTransformer") != null;
      Object listener = field(harness, null, "transformerListener");
      if (!registered || !installed || listener == null) {
        throw new IllegalStateException(
            "Coverage requires a registered tracer and active production transformer");
      }
      Collection<?> names =
          (Collection<?>) field(listener.getClass(), listener, "transformedClassesNames");
      Collection<String> requiredNames = new ArrayList<>();
      Collection<String> missingNames = new ArrayList<>();
      for (String required :
          System.getProperty("test.context.coverage.require-transformed", "").split(",")) {
        String trimmed = required.trim();
        if (!trimmed.isEmpty()) {
          requiredNames.add(trimmed);
          if (!names.contains(trimmed)) {
            missingNames.add(trimmed);
          }
        }
      }
      ClassLoader contextLoader =
          Class.forName("datadog.context.Context", false, loader).getClassLoader();
      if (contextLoader != null) {
        throw new IllegalStateException(
            "Coverage must observe the bootstrap Context implementation");
      }
      coverage.metadata("contextClassLoader", "bootstrap");
      coverage.metadata("agentInstalled", true);
      coverage.metadata("agentTransformerInstalled", true);
      coverage.metadata(
          "tracerClass", agentTracer.getMethod("get").invoke(null).getClass().getName());
      coverage.metadata("productionTransformedClasses", new ArrayList<>(names));
      coverage.metadata("requiredProductionTransforms", requiredNames);
      coverage.metadata("missingProductionTransforms", missingNames);
    }

    private void finish() throws Exception {
      if (finished || coverage == null) {
        return;
      }
      finished = true;
      coverage.pause();
      Exception failure = null;
      try {
        coverage.close();
      } catch (Exception error) {
        failure = error;
      }
      try {
        coverage.writeReport(output);
      } catch (Exception error) {
        if (failure == null) {
          throw error;
        }
        failure.addSuppressed(error);
      }
      if (failure != null) {
        throw failure;
      }
    }
  }
}
