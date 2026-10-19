package datadog.trace.instrumentation.testng.execution;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.testng.IAnnotationTransformer;
import org.testng.IRetryAnalyzer;
import org.testng.TestNG;
import org.testng.annotations.ITestAnnotation;

public class RetryAnnotationTransformer implements IAnnotationTransformer {
  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(TestNG.class.getClassLoader());
  private static final MethodHandle GET_RETRY_ANALYZER_CLASS =
      METHOD_HANDLES.method(ITestAnnotation.class, "getRetryAnalyzerClass");
  private static final MethodHandle GET_RETRY_ANALYZER =
      METHOD_HANDLES.method(ITestAnnotation.class, "getRetryAnalyzer");
  private static final Map<ITestAnnotation, Boolean> FRAMEWORK_OVERRIDES =
      Collections.synchronizedMap(new WeakHashMap<ITestAnnotation, Boolean>());
  private final IAnnotationTransformer delegate;

  public RetryAnnotationTransformer(IAnnotationTransformer delegate) {
    this.delegate = delegate;
  }

  public static boolean hasFrameworkOverride(ITestAnnotation annotation) {
    if (annotation == null) {
      return false;
    }
    Boolean overridden = FRAMEWORK_OVERRIDES.get(annotation);
    // A user-installed transformer can replace ours after TestNG construction.
    return overridden == null || overridden;
  }

  private static Class<?> retryAnalyzerClass(ITestAnnotation annotation) {
    if (GET_RETRY_ANALYZER_CLASS != null) {
      return METHOD_HANDLES.invoke(GET_RETRY_ANALYZER_CLASS, annotation);
    }
    IRetryAnalyzer analyzer = METHOD_HANDLES.invoke(GET_RETRY_ANALYZER, annotation);
    return analyzer != null ? analyzer.getClass() : null;
  }

  @Override
  public void transform(
      ITestAnnotation annotation, Class testClass, Constructor testConstructor, Method testMethod) {
    FRAMEWORK_OVERRIDES.put(annotation, false);
    Class<?> nativeAnalyzer = retryAnalyzerClass(annotation);
    annotation.setRetryAnalyzer(RetryAnalyzer.class);
    if (delegate != null) {
      delegate.transform(annotation, testClass, testConstructor, testMethod);
    }
    if (retryAnalyzerClass(annotation) != RetryAnalyzer.class) {
      // Preserve the existing precedence of explicit user transformer overrides.
      FRAMEWORK_OVERRIDES.put(annotation, true);
    } else if (nativeAnalyzer != null) {
      // Let TestNG resolve class inheritance and effective groups before selecting DD policy.
      annotation.setRetryAnalyzer(nativeAnalyzer);
    }
  }
}
