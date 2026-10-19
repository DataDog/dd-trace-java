package datadog.trace.instrumentation.testng.execution;

import datadog.trace.api.civisibility.config.TestExclusions;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.instrumentation.testng.TestNGUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.testng.IAnnotationTransformer;
import org.testng.IRetryAnalyzer;
import org.testng.annotations.ITestAnnotation;
import org.testng.annotations.Test;

public class RetryAnnotationTransformer implements IAnnotationTransformer {
  private final IAnnotationTransformer delegate;

  public RetryAnnotationTransformer(IAnnotationTransformer delegate) {
    this.delegate = delegate;
  }

  @Override
  public void transform(
      ITestAnnotation annotation, Class testClass, Constructor testConstructor, Method testMethod) {
    // TestNG 6 has no Datadog execution-policy instrumentation. Keep explicit native retries.
    Test declaration = testMethod != null ? testMethod.getAnnotation(Test.class) : null;
    String frameworkVersion = TestNGUtils.getTestNGVersion();
    boolean preserveFrameworkRetry =
        frameworkVersion != null
            && frameworkVersion.startsWith("6.")
            && declaration != null
            && IRetryAnalyzer.class.isAssignableFrom(declaration.retryAnalyzer())
            && TestExclusions.noAutoRetry(
                new TestSourceData(testClass, testMethod), Arrays.asList(annotation.getGroups()));
    if (!preserveFrameworkRetry) {
      annotation.setRetryAnalyzer(RetryAnalyzer.class);
    }
    if (delegate != null) {
      delegate.transform(annotation, testClass, testConstructor, testMethod);
    }
  }
}
