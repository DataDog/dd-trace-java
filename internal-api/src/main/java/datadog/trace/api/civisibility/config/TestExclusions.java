package datadog.trace.api.civisibility.config;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;

/** Resolves test-local declarations without retaining application classes between runs. */
public final class TestExclusions {
  private TestExclusions() {}

  public static boolean noAutoRetry(TestSourceData source, Collection<String> tags) {
    return declared(source, tags, "NoAutoRetry", "datadog_no_auto_retry");
  }

  public static boolean preventFailureSuppression(TestSourceData source, Collection<String> tags) {
    return declared(
        source, tags, "PreventFailureSuppression", "datadog_prevent_failure_suppression");
  }

  private static boolean declared(
      TestSourceData source, Collection<String> tags, String annotationName, String tag) {
    if (tags != null && tags.contains(tag)) {
      return true;
    }
    Method method = source.getTestMethod();
    if (method != null) {
      for (Annotation annotation : method.getDeclaredAnnotations()) {
        if (annotation
            .annotationType()
            .getName()
            .equals("datadog.trace.api.civisibility." + annotationName)) {
          return true;
        }
      }
    }
    return false;
  }
}
