package datadog.trace.agent.test.coverage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import org.spockframework.runtime.extension.IGlobalExtension;
import org.spockframework.runtime.extension.IMethodInvocation;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.SpecInfo;
import org.spockframework.runtime.model.parallel.ExecutionMode;

/** Opt-in observation of existing instrumentation-test feature bodies, without creating context. */
public final class InstrumentationCoverageExtension implements IGlobalExtension {
  private static final String SPECIFICATION =
      "datadog.trace.agent.test.InstrumentationSpecification";

  @Override
  public void visitSpec(SpecInfo spec) {
    String classes = System.getProperty("test.context.coverage.classes", "").trim();
    if (classes.isEmpty() || !isInstrumentationSpec(spec.getReflection())) {
      return;
    }
    String output = System.getProperty("test.context.coverage.output", "").trim();
    if (output.isEmpty()) {
      throw new IllegalArgumentException("test.context.coverage.output must be a report directory");
    }
    Session session = new Session(spec, classes, Paths.get(output));
    // A collector is process-wide; overlapping iterations would blur observation windows.
    spec.setExecutionMode(ExecutionMode.SAME_THREAD);
    spec.setChildExecutionMode(ExecutionMode.SAME_THREAD);
    spec.addInterceptor(session::runSpec);
    spec.addSetupSpecInterceptor(session::setupSpec);
    spec.addCleanupSpecInterceptor(session::cleanupSpec);
    for (FeatureInfo feature : spec.getAllFeatures()) {
      feature.getFeatureMethod().addInterceptor(session::runFeature);
    }
  }

  private static boolean isInstrumentationSpec(Class<?> type) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      if (SPECIFICATION.equals(current.getName())) {
        return true;
      }
    }
    return false;
  }

  private static final class Session {
    private final SpecInfo spec;
    private final String classes;
    private final Path output;
    private ContextCoverage coverage;
    private boolean finished;

    private Session(SpecInfo spec, String classes, Path output) {
      this.spec = spec;
      this.classes = classes;
      String runId = System.getProperty("test.context.coverage.run-id", "");
      Path workerOutput =
          runId.isEmpty()
              ? output
              : output.resolve("worker-" + System.getProperty("org.gradle.test.worker", "unknown"));
      this.output = workerOutput.resolve(spec.getReflection().getName());
    }

    private void runSpec(IMethodInvocation invocation) throws Throwable {
      Throwable failure = null;
      try {
        invocation.proceed();
      } catch (Throwable thrown) {
        failure = thrown;
        throw thrown;
      } finally {
        try {
          finish();
        } catch (Throwable cleanupFailure) {
          if (failure == null) {
            throw cleanupFailure;
          }
          failure.addSuppressed(cleanupFailure);
        }
      }
    }

    private void setupSpec(IMethodInvocation invocation) throws Throwable {
      invocation.proceed();
      String[] names = classes.split(",", -1);
      Class<?>[] targets = new Class<?>[names.length];
      ClassLoader loader = InstrumentationCoverageExtension.class.getClassLoader();
      for (int i = 0; i < names.length; i++) {
        targets[i] = Class.forName(names[i].trim(), false, loader);
        if (targets[i].getClassLoader() != null && targets[i].getClassLoader() != loader) {
          throw new IllegalArgumentException(
              "Target must use bootstrap or the observer loader: " + names[i]);
        }
      }
      // setupSpec installs the production transformer and applies load-time test configuration.
      coverage = ContextCoverage.observe(targets);
      coverage.pause();
      coverage.metadata("mode", "instrumentation-test");
      coverage.metadata("specName", spec.getReflection().getName());
      coverage.metadata("runId", System.getProperty("test.context.coverage.run-id", ""));
      coverage.metadata("workerId", System.getProperty("org.gradle.test.worker", "unknown"));
      coverage.metadata("agentInstalled", false);
      coverage.metadata("agentTransformerInstalled", false);
      coverage.metadata(
          "scenarioAttribution",
          "stable per-iteration identity; initiating-thread attribution is exact and worker "
              + "attribution is temporal within one serialized test window");
    }

    private void runFeature(IMethodInvocation invocation) throws Throwable {
      verifyAgent(invocation.getSharedInstance());
      Throwable[] failure = new Throwable[1];
      String scenarioName = invocation.getIteration().getDisplayName();
      String scenarioId =
          "spock:"
              + spec.getReflection().getName()
              + "#"
              + invocation.getIteration().getFeature().getFeatureMethod().getName()
              + "["
              + invocation.getIteration().getIterationIndex()
              + "]";
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

    private void verifyAgent(Object sharedInstance) throws ReflectiveOperationException {
      Class<?> tracer =
          Class.forName(
              "datadog.trace.bootstrap.instrumentation.api.AgentTracer",
              false,
              InstrumentationCoverageExtension.class.getClassLoader());
      boolean registered = Boolean.TRUE.equals(tracer.getMethod("isRegistered").invoke(null));
      boolean installed = sharedProperty(sharedInstance, "activeTransformer") != null;
      ClassLoader contextLoader =
          Class.forName(
                  "datadog.context.Context",
                  false,
                  InstrumentationCoverageExtension.class.getClassLoader())
              .getClassLoader();
      coverage.metadata(
          "contextClassLoader", contextLoader == null ? "bootstrap" : contextLoader.toString());
      Object transformed = sharedProperty(sharedInstance, "TRANSFORMED_CLASSES_NAMES");
      if (!(transformed instanceof Collection)) {
        throw new IllegalStateException("Production transformed-class inventory is unavailable");
      }
      Collection<?> transformedNames = (Collection<?>) transformed;
      coverage.metadata("productionTransformedClasses", new ArrayList<>(transformedNames));
      String required = System.getProperty("test.context.coverage.require-transformed", "").trim();
      Collection<String> requiredNames = new ArrayList<>();
      Collection<String> missingNames = new ArrayList<>();
      if (!required.isEmpty()) {
        for (String name : required.split(",", -1)) {
          String trimmed = name.trim();
          if (!trimmed.isEmpty()) {
            requiredNames.add(trimmed);
            if (!transformedNames.contains(trimmed)) {
              missingNames.add(trimmed);
            }
          }
        }
      }
      coverage.metadata("requiredProductionTransforms", requiredNames);
      coverage.metadata("missingProductionTransforms", missingNames);
      coverage.metadata("agentTransformerInstalled", installed);
      coverage.metadata("agentInstalled", registered && installed);
      coverage.metadata("tracerClass", tracer.getMethod("get").invoke(null).getClass().getName());
      if (!registered || !installed) {
        throw new IllegalStateException(
            "Context coverage requires a registered tracer and active production transformer");
      }
    }

    private void cleanupSpec(IMethodInvocation invocation) throws Throwable {
      Throwable observationFailure = null;
      try {
        // Reset while the production transformer is still present to preserve its advice.
        finish();
      } catch (Throwable thrown) {
        observationFailure = thrown;
      }
      try {
        invocation.proceed();
      } catch (Throwable testFailure) {
        if (observationFailure != null) {
          testFailure.addSuppressed(observationFailure);
        }
        throw testFailure;
      }
      if (observationFailure != null) {
        throw observationFailure;
      }
    }

    private void finish() throws Throwable {
      if (coverage == null || finished) {
        return;
      }
      finished = true;
      coverage.pause();
      Throwable failure = null;
      try {
        coverage.close();
      } catch (Throwable thrown) {
        failure = thrown;
      }
      try {
        coverage.writeReport(output);
      } catch (Throwable reportFailure) {
        if (failure == null) {
          throw reportFailure;
        }
        failure.addSuppressed(reportFailure);
      }
      if (failure != null) {
        throw failure;
      }
    }
  }

  private static Object sharedProperty(Object sharedInstance, String name)
      throws ReflectiveOperationException {
    try {
      Method getter =
          sharedInstance
              .getClass()
              .getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
      return getter.invoke(sharedInstance);
    } catch (NoSuchMethodException missingGetter) {
      for (Class<?> type = sharedInstance.getClass(); type != null; type = type.getSuperclass()) {
        for (Field field : type.getDeclaredFields()) {
          // Spock rewrites @Shared fields while keeping the Groovy property getter.
          if (field.getName().equals(name)
              || field.getName().equals("$spock_sharedField_" + name)) {
            field.setAccessible(true);
            return field.get(sharedInstance);
          }
        }
      }
      throw missingGetter;
    }
  }
}
