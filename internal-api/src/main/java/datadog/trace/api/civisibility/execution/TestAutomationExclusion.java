package datadog.trace.api.civisibility.execution;

import datadog.trace.api.civisibility.NoAutoRetry;
import datadog.trace.api.civisibility.PreventFailureSuppression;
import datadog.trace.api.civisibility.config.TestSourceData;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Test-local execution exclusions declared through public annotations or native framework tags. */
public enum TestAutomationExclusion {
  NO_AUTO_RETRY(NoAutoRetry.TAG, NoAutoRetry.class),
  PREVENT_FAILURE_SUPPRESSION(PreventFailureSuppression.TAG, PreventFailureSuppression.class);

  private final String tag;
  private final Class<? extends Annotation> annotationType;

  TestAutomationExclusion(String tag, Class<? extends Annotation> annotationType) {
    this.tag = tag;
    this.annotationType = annotationType;
  }

  /** Returns whether native tags or the test method declare this exclusion. */
  public boolean isDeclared(@Nonnull TestSourceData source, @Nullable Collection<String> tags) {
    if (tags != null && tags.contains(tag)) {
      return true;
    }
    Method method = source.getTestMethod();
    return method != null && method.isAnnotationPresent(annotationType);
  }
}
