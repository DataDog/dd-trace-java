package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;

public abstract class JUnit5Utils {

  public static Class<?> getJavaClass(TestIdentifier testIdentifier) {
    TestSource testSource = testIdentifier.getSource().orElse(null);
    if (testSource instanceof ClassSource) {
      ClassSource classSource = (ClassSource) testSource;
      return classSource.getJavaClass();

    } else if (testSource instanceof MethodSource) {
      MethodSource methodSource = (MethodSource) testSource;
      return TestFrameworkUtils.getTestClass(methodSource);

    } else {
      return null;
    }
  }

  /*
   * JUnit5 considers parameterized or factory test cases as containers.
   * We need to differentiate this type of containers from "regular" ones, that are test classes
   */
  public static boolean isTestCase(TestIdentifier testIdentifier) {
    return testIdentifier.isContainer() && getMethodSourceOrNull(testIdentifier) != null;
  }

  public static boolean isRootContainer(TestIdentifier testIdentifier) {
    return !testIdentifier.getParentId().isPresent();
  }

  private static MethodSource getMethodSourceOrNull(TestIdentifier testIdentifier) {
    return (MethodSource)
        testIdentifier.getSource().filter(s -> s instanceof MethodSource).orElse(null);
  }

  public static boolean isAssumptionFailure(Throwable throwable) {
    switch (throwable.getClass().getName()) {
      case "org.junit.AssumptionViolatedException":
      case "org.junit.internal.AssumptionViolatedException":
      case "org.opentest4j.TestAbortedException":
      case "org.opentest4j.TestSkippedException":
        // If the test assumption fails, one of the following exceptions will be thrown.
        // The consensus is to treat "assumptions failure" as skipped tests.
        return true;
      default:
        return false;
    }
  }

  public static Collection<TestEngine> getTestEngines(LauncherConfig config) {
    Set<TestEngine> engines = new LinkedHashSet<>();
    if (config.isTestEngineAutoRegistrationEnabled()) {
      ClassLoader defaultClassLoader = ClassLoaderUtils.getDefaultClassLoader();
      ServiceLoader.load(TestEngine.class, defaultClassLoader).forEach(engines::add);
    }
    engines.addAll(config.getAdditionalTestEngines());
    return engines;
  }

  public static boolean isTestInProgress() {
    AgentScope activeScope = AgentTracer.activeScope();
    if (activeScope == null) {
      return false;
    }
    AgentSpan span = activeScope.span();
    if (span == null) {
      return false;
    }
    return InternalSpanTypes.TEST.toString().equals(span.getSpanType());
  }
}
