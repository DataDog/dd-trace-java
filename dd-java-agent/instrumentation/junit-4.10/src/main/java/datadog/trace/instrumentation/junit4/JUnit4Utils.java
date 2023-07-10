package datadog.trace.instrumentation.junit4;

import datadog.trace.util.Strings;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class JUnit4Utils {

  private static final Logger log = LoggerFactory.getLogger(JUnit4Utils.class);
  private static final String SYNCHRONIZED_LISTENER =
      "org.junit.runner.notification.SynchronizedRunListener";

  // Regex for the final brackets with its content in the test name. E.g. test_name[0] --> [0]
  private static final Pattern testNameNormalizerRegex = Pattern.compile("\\[[^\\[]*\\]$");

  private static final Pattern METHOD_AND_CLASS_NAME_PATTERN =
      Pattern.compile("([\\s\\S]*)\\((.*)\\)");

  private static final Method DESCRIBE_CHILD = accesDescribeChildMethod();

  private static Method accesDescribeChildMethod() {
    try {
      Method describeChild = ParentRunner.class.getDeclaredMethod("describeChild", Object.class);
      describeChild.setAccessible(true);
      return describeChild;
    } catch (Exception e) {
      return null;
    }
  }

  public static List<RunListener> runListenersFromRunNotifier(final RunNotifier runNotifier) {
    try {

      Field listeners;
      try {
        // Since JUnit 4.12, the field is called "listeners"
        listeners = runNotifier.getClass().getDeclaredField("listeners");
      } catch (final NoSuchFieldException e) {
        // Before JUnit 4.12, the field is called "fListeners"
        listeners = runNotifier.getClass().getDeclaredField("fListeners");
      }

      listeners.setAccessible(true);
      return (List<RunListener>) listeners.get(runNotifier);
    } catch (final Throwable e) {
      log.debug("Could not get runListeners for JUnit4Advice", e);
      return null;
    }
  }

  public static boolean isTracingListener(final RunListener listener) {
    return listener instanceof TracingListener;
  }

  public static boolean isJUnitVintageListener(final RunListener listener) {
    Class<? extends RunListener> listenerClass = listener.getClass();
    String listenerClassName = listenerClass.getName();
    return listenerClassName.startsWith("org.junit.vintage");
  }

  public static RunListener unwrapListener(final RunListener listener) {
    if (SYNCHRONIZED_LISTENER.equals(listener.getClass().getName())) {
      try {
        // There is no public accessor to the inner listener.
        final Field innerListener = listener.getClass().getDeclaredField("listener");
        innerListener.setAccessible(true);
        return (RunListener) innerListener.get(listener);
      } catch (final Throwable e) {
        log.debug("Could not get inner listener from SynchronizedRunListener", e);
      }
    }
    return listener;
  }

  public static TracingListener toTracingListener(final RunListener listener) {
    if (listener instanceof TracingListener) {
      return (TracingListener) listener;
    }

    // Since JUnit 4.12, the RunListener are wrapped by a SynchronizedRunListener object.
    if (SYNCHRONIZED_LISTENER.equals(listener.getClass().getName())) {
      try {
        // There is no public accessor to the inner listener.
        final Field innerListenerField = listener.getClass().getDeclaredField("listener");
        innerListenerField.setAccessible(true);

        Object innerListener = innerListenerField.get(listener);
        if (innerListener instanceof TracingListener) {
          return (TracingListener) innerListener;
        }
      } catch (final Throwable e) {
        log.debug("Could not get inner listener from SynchronizedRunListener", e);
      }
    }

    return null;
  }

  @Nullable
  public static Method getTestMethod(final Description description) {
    Class<?> testClass = description.getTestClass();
    if (testClass == null) {
      return null;
    }

    String methodName = description.getMethodName();
    if (methodName == null || methodName.isEmpty()) {
      return null;
    }

    int actualMethodNameStart, actualMethodNameEnd;
    if ((actualMethodNameStart = methodName.indexOf('(')) > 0
        && (actualMethodNameEnd = methodName.indexOf(')', actualMethodNameStart)) > 0) {
      // assuming this is a parameterized test case that uses use pl.pragmatists.JUnitParams
      // in that case method name will have the following structure:
      // [test case number] param1, param2, param3 (methodName)
      // e.g. [0] 2, 2, 4 (shouldReturnCorrectSum)

      int parameterCount = countCharacter(methodName, ',') + 1;

      methodName = methodName.substring(actualMethodNameStart + 1, actualMethodNameEnd);

      // below is a best-effort attempt to find a matching method with the information we have
      // this is not terribly efficient, but this case should be rare
      for (Method declaredMethod : testClass.getDeclaredMethods()) {
        if (!declaredMethod.getName().equals(methodName)) {
          continue;
        }

        if (declaredMethod.getParameterCount() != parameterCount) {
          continue;
        }

        for (Annotation annotation : declaredMethod.getAnnotations()) {
          // comparing by name to avoid having to add JUnitParams dependency
          if (annotation.annotationType().getName().equals("junitparams.Parameters")) {
            return declaredMethod;
          }
        }
      }
    } else if (methodName.contains("[")) {
      methodName = testNameNormalizerRegex.matcher(methodName).replaceAll("");
    }

    try {
      return testClass.getMethod(methodName);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static int countCharacter(String s, char countedChar) {
    int count = 0;
    for (char c : s.toCharArray()) {
      if (c == countedChar) {
        count++;
      }
    }
    return count;
  }

  public static String getTestName(final Description description, final Method testMethod) {
    final String methodName = description.getMethodName();
    if (methodName != null && !methodName.isEmpty()) {
      int actualMethodNameStart, actualMethodNameEnd;
      if (methodName.startsWith("[")
          && (actualMethodNameStart = methodName.indexOf('(')) > 0
          && (actualMethodNameEnd = methodName.indexOf(')', actualMethodNameStart)) > 0) {
        // assuming this is a parameterized test case that uses use pl.pragmatists.JUnitParams
        // in that case method name will have the following structure:
        // [test case number] param1, param2, param3 (methodName)
        // e.g. [0] 2, 2, 4 (shouldReturnCorrectSum)
        return methodName.substring(actualMethodNameStart + 1, actualMethodNameEnd);

      } else {
        // For "regular" parameterized tests, the test name contains a custom test name
        // within the brackets. e.g. parameterized_test[0].
        // For the test.name tag, we need to normalize the test names.
        // "parameterized_test[0]" must be "parameterized_test".

        // We could use a simple .endsWith function for pure JUnit4 tests,
        // however, we need to use a regex to find the trailing brackets specifically because if the
        // test is based on Spock v1 (that runs JUnit4 listeners under the hood)
        // there are no test name restrictions (it can be any string).
        return testNameNormalizerRegex.matcher(methodName).replaceAll("");
      }
    }

    if (testMethod != null && !testMethod.getName().isEmpty()) {
      return testMethod.getName();
    }

    String displayName = description.getDisplayName();
    if (displayName != null && !displayName.isEmpty()) {
      // This extra fallback avoids reporting empty names when using Cucumber.
      // A Description generated by a Cucumber Scenario doesn't have a test or
      // method name but does have a display name.
      return description.getDisplayName();
    }

    return null;
  }

  public static String getParameters(final Description description) {
    final String methodName = description.getMethodName();
    if (methodName == null || !methodName.contains("[")) {
      return null;
    }

    // No public access to the test parameters map in JUnit4.
    // In this case, we store the fullTestName in the "metadata.test_name" object.
    return "{\"metadata\":{\"test_name\":\"" + Strings.escapeToJson(methodName) + "\"}}";
  }

  public static List<String> getCategories(Class<?> testClass, @Nullable Method testMethod) {
    List<String> categories = new ArrayList<>();

    while (testClass != null) {
      Category categoryAnnotation = testClass.getAnnotation(Category.class);
      if (categoryAnnotation != null) {
        for (Class<?> category : categoryAnnotation.value()) {
          categories.add(category.getName());
        }
      }
      // Category annotation is not inherited in versions before 4.12
      testClass = testClass.getSuperclass();
    }

    if (testMethod != null) {
      Category categoryAnnotation = testMethod.getAnnotation(Category.class);
      if (categoryAnnotation != null) {
        for (Class<?> category : categoryAnnotation.value()) {
          categories.add(category.getName());
        }
      }
    }

    return categories;
  }

  public static boolean isTestCaseDescription(final Description description) {
    return description.getMethodName() != null && !"".equals(description.getMethodName());
  }

  public static boolean isTestSuiteDescription(final Description description) {
    return description.getTestClass() != null && !isTestCaseDescription(description);
  }

  public static List<Method> getTestMethods(final Class<?> testClass) {
    final List<Method> testMethods = new ArrayList<>();

    final Method[] methods = testClass.getMethods();
    for (final Method method : methods) {
      if (method.getAnnotation(Test.class) != null) {
        testMethods.add(method);
      }
    }
    return testMethods;
  }

  public static Description getDescription(ParentRunner runner, Object child) {
    try {
      if (DESCRIBE_CHILD != null) {
        return (Description) DESCRIBE_CHILD.invoke(runner, child);
      }
    } catch (Exception e) {
      log.error("Could not describe child: " + child, e);
    }
    return null;
  }

  public static Description getSkippedDescription(Description description) {
    Collection<Annotation> annotations = description.getAnnotations();
    Annotation[] updatedAnnotations = new Annotation[annotations.size() + 1];
    int idx = 0;
    for (Annotation annotation : annotations) {
      updatedAnnotations[idx++] = annotation;
    }
    updatedAnnotations[idx] = new SkippedByItr();

    String displayName = description.getDisplayName();
    Matcher matcher = METHOD_AND_CLASS_NAME_PATTERN.matcher(displayName);
    String name = matcher.matches() ? matcher.group(1) : getTestName(description, null);

    return Description.createTestDescription(description.getTestClass(), name, updatedAnnotations);
  }
}
