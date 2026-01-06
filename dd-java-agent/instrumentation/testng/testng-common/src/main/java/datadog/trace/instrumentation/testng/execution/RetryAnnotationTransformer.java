package datadog.trace.instrumentation.testng.execution;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.testng.IAnnotationTransformer;
import org.testng.annotations.ITestAnnotation;

public class RetryAnnotationTransformer implements IAnnotationTransformer {
  private final IAnnotationTransformer delegate;

  public RetryAnnotationTransformer(IAnnotationTransformer delegate) {
    this.delegate = delegate;
  }

  @Override
  public void transform(
      ITestAnnotation annotation, Class testClass, Constructor testConstructor, Method testMethod) {
    annotation.setRetryAnalyzer(RetryAnalyzer.class);
    if (delegate != null) {
      delegate.transform(annotation, testClass, testConstructor, testMethod);
    }
  }
}
