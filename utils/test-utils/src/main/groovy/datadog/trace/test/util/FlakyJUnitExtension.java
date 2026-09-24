package datadog.trace.test.util;

import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Predicate;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Selects JUnit tests using the same flaky-test modes as {@link FlakySpockExtension}. */
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
    return ConditionEvaluationResult.disabled(
        flaky.value().isEmpty() ? "Flaky test" : "Flaky test: " + flaky.value());
  }

  static Flaky findFlaky(Class<?> testClass, Method method) {
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
    return method == null ? null : matchingAnnotation(method, testClass);
  }

  private static Flaky matchingAnnotation(AnnotatedElement element, Class<?> testClass) {
    Flaky flaky = findAnnotation(element, Flaky.class).orElse(null);
    if (flaky == null) {
      return null;
    }
    if (flaky.suites().length > 0) {
      boolean matches = false;
      for (String suite : flaky.suites()) {
        if (suite.equals(testClass.getSimpleName()) || suite.equals(testClass.getName())) {
          matches = true;
          break;
        }
      }
      if (!matches) {
        return null;
      }
    }
    if (flaky.condition() == Flaky.True.class) {
      return flaky;
    }
    try {
      Constructor<? extends Predicate<String>> constructor =
          flaky.condition().getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance().test(testClass.getSimpleName()) ? flaky : null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new ExtensionConfigurationException(
          "Could not evaluate @Flaky condition " + flaky.condition().getName() + " on " + element,
          e);
    }
  }
}
