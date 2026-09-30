package datadog.trace.test.util;

import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Selects JUnit tests using the project's flaky-test modes. */
public final class FlakyJUnitExtension implements ExecutionCondition {
  private static final String RUN_FLAKY_TESTS = "run.flaky.tests";

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (!"false".equals(System.getProperty(RUN_FLAKY_TESTS))
        || !context.getTestClass().isPresent()) {
      return ConditionEvaluationResult.enabled("Flaky tests are not skipped");
    }
    Flaky flaky = findFlaky(context.getRequiredTestClass(), context.getTestMethod().orElse(null));
    if (flaky == null) {
      return ConditionEvaluationResult.enabled("Test is not flaky");
    }
    if (!context.getTestMethod().isPresent() && flaky.suites().length > 0) {
      return ConditionEvaluationResult.enabled("Container may contain tests outside flaky suites");
    }
    return ConditionEvaluationResult.disabled(
        flaky.value().isEmpty() ? "Flaky test" : "Flaky test: " + flaky.value());
  }

  static Flaky findFlaky(Class<?> testClass, Method method) {
    if (method != null) {
      Flaky flaky = matchingAnnotation(method, testClass);
      if (flaky != null) {
        return flaky;
      }
    }
    for (Class<?> enclosing = testClass;
        enclosing != null;
        enclosing = enclosing.getEnclosingClass()) {
      for (Class<?> current = enclosing; current != null; current = current.getSuperclass()) {
        Flaky flaky = matchingAnnotation(current, testClass);
        if (flaky != null) {
          return flaky;
        }
      }
    }
    return null;
  }

  private static Flaky matchingAnnotation(AnnotatedElement element, Class<?> testClass) {
    Flaky flaky = findAnnotation(element, Flaky.class).orElse(null);
    if (flaky == null) {
      return null;
    }
    if (flaky.suites().length > 0) {
      boolean matches = false;
      for (String suite : flaky.suites()) {
        if (matchesClassName(suite, testClass)) {
          matches = true;
          break;
        }
      }
      if (!matches) {
        return null;
      }
    }
    if (flaky.conditionMethod().isEmpty()) {
      if (flaky.condition() != Flaky.True.class) {
        throw new ExtensionConfigurationException(
            "JUnit @Flaky conditions must use conditionMethod");
      }
      return flaky;
    }
    if (flaky.condition() != Flaky.True.class) {
      throw new ExtensionConfigurationException(
          "@Flaky cannot set both condition and conditionMethod");
    }
    try {
      return evaluateConditionMethod(flaky.conditionMethod(), testClass.getClassLoader())
          ? flaky
          : null;
    } catch (RuntimeException e) {
      throw new ExtensionConfigurationException(e.getMessage(), e);
    }
  }

  private static boolean evaluateConditionMethod(String reference, ClassLoader classLoader) {
    int separator = reference.lastIndexOf('#');
    if (separator <= 0 || separator == reference.length() - 1) {
      throw new IllegalArgumentException(
          "@Flaky condition method must use the format fully.qualified.Class#method");
    }

    String className = reference.substring(0, separator);
    String methodName = reference.substring(separator + 1);
    try {
      Class<?> conditionClass = classLoader.loadClass(className);
      Method method = conditionClass.getDeclaredMethod(methodName);
      if (!Modifier.isStatic(method.getModifiers())) {
        throw new IllegalArgumentException(
            "@Flaky condition method " + reference + " must be static");
      }
      if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
        throw new IllegalArgumentException(
            "@Flaky condition method " + reference + " must return boolean");
      }
      method.setAccessible(true);
      return Boolean.TRUE.equals(method.invoke(null));
    } catch (InvocationTargetException e) {
      throw new IllegalArgumentException(
          "Could not evaluate @Flaky condition method " + reference, e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException(
          "Could not evaluate @Flaky condition method " + reference, e);
    }
  }

  private static boolean matchesClassName(String suite, Class<?> testClass) {
    return suite.equals(testClass.getSimpleName()) || suite.equals(testClass.getCanonicalName());
  }
}
