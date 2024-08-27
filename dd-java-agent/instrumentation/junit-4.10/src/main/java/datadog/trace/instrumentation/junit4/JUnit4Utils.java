package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import junit.framework.TestCase;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class JUnit4Utils {

  private static final Logger LOGGER = LoggerFactory.getLogger(JUnit4Utils.class);

  private static final String SYNCHRONIZED_LISTENER =
      "org.junit.runner.notification.SynchronizedRunListener";

  // Regex for the final brackets with its content in the test name. E.g. test_name[0] --> [0]
  private static final Pattern testNameNormalizerRegex = Pattern.compile("\\[[^\\[]*\\]$");

  private static final Pattern METHOD_AND_CLASS_NAME_PATTERN =
      Pattern.compile("([\\s\\S]*)\\((.*)\\)");

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ParentRunner.class.getClassLoader());
  private static final MethodHandle PARENT_RUNNER_DESCRIBE_CHILD =
      METHOD_HANDLES.method(ParentRunner.class, "describeChild", Object.class);
  private static final MethodHandle RUN_NOTIFIER_LISTENERS = accessListenersFieldInRunNotifier();
  private static final MethodHandle INNER_SYNCHRONIZED_LISTENER =
      accessListenerFieldInSynchronizedListener();
  private static final MethodHandle DESCRIPTION_UNIQUE_ID =
      METHOD_HANDLES.privateFieldGetter(Description.class, "fUniqueId");

  private static MethodHandle accessListenersFieldInRunNotifier() {
    MethodHandle listeners = METHOD_HANDLES.privateFieldGetter(RunNotifier.class, "listeners");
    if (listeners != null) {
      return listeners;
    }
    // Before JUnit 4.12, the field is called "fListeners"
    return METHOD_HANDLES.privateFieldGetter(RunNotifier.class, "fListeners");
  }

  private static MethodHandle accessListenerFieldInSynchronizedListener() {
    MethodHandle handle = METHOD_HANDLES.privateFieldGetter(SYNCHRONIZED_LISTENER, "listener");
    if (handle != null) {
      return handle;
    }
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    return new MethodHandles(contextClassLoader)
        .privateFieldGetter(SYNCHRONIZED_LISTENER, "listener");
  }

  public static List<RunListener> runListenersFromRunNotifier(final RunNotifier runNotifier) {
    return METHOD_HANDLES.invoke(RUN_NOTIFIER_LISTENERS, runNotifier);
  }

  public static TracingListener toTracingListener(final RunListener listener) {
    // Since JUnit 4.12, the RunListener are wrapped by a SynchronizedRunListener object.
    if (SYNCHRONIZED_LISTENER.equals(listener.getClass().getName())) {
      RunListener innerListener = METHOD_HANDLES.invoke(INNER_SYNCHRONIZED_LISTENER, listener);
      if (innerListener instanceof TracingListener) {
        return (TracingListener) innerListener;
      }
    }
    if (listener instanceof TracingListener) {
      return (TracingListener) listener;
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

    RunWith runWith = testClass.getAnnotation(RunWith.class);
    boolean isJunitParamsTestCase =
        runWith != null && "junitparams.JUnitParamsRunner".equals(runWith.value().getName());
    int junitParamsStartIdx;
    if (isJunitParamsTestCase && (junitParamsStartIdx = methodName.indexOf('(')) >= 0) {
      // assuming this is a parameterized test case that uses use pl.pragmatists.JUnitParams
      // in that case method name will have the following structure:
      // methodName(param1, param2, param3) [test case number]
      // e.g. test_parameterized(1, 2, 3) [0]

      int parameterCount = countCharacter(methodName, ',') + 1;
      methodName = methodName.substring(0, junitParamsStartIdx);

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
      LOGGER.debug("Could not get method named {} in class {}", methodName, testClass, e);
      LOGGER.warn("Could not get test method", e);
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

  public static boolean isSuiteContainingChildren(final Description description) {
    Class<?> testClass = description.getTestClass();
    if (testClass == null) {
      return false;
    }
    for (Method method : testClass.getMethods()) {
      if (method.getAnnotation(Test.class) != null) {
        return true;
      }
    }
    return TestCase.class.isAssignableFrom(testClass);
  }

  public static Object getUniqueId(final Description description) {
    return METHOD_HANDLES.invoke(DESCRIPTION_UNIQUE_ID, description);
  }

  public static String getSuiteName(final Class<?> testClass, final Description description) {
    return testClass != null ? testClass.getName() : description.getClassName();
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

  public static Description getDescription(ParentRunner<?> runner, Object child) {
    return METHOD_HANDLES.invoke(PARENT_RUNNER_DESCRIBE_CHILD, runner, child);
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
    Class<?> testClass = description.getTestClass();
    Matcher matcher = METHOD_AND_CLASS_NAME_PATTERN.matcher(displayName);
    String name = matcher.matches() ? matcher.group(1) : getTestName(description, null);
    return Description.createTestDescription(testClass, name, updatedAnnotations);
  }

  public static TestIdentifier toTestIdentifier(Description description) {
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String suite = description.getClassName();
    String name = JUnit4Utils.getTestName(description, testMethod);
    String parameters = JUnit4Utils.getParameters(description);
    return new TestIdentifier(suite, name, parameters, null);
  }

  public static TestDescriptor toTestDescriptor(Description description) {
    Class<?> testClass = description.getTestClass();
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    return new TestDescriptor(testSuiteName, testClass, testName, testParameters, null);
  }

  public static TestSuiteDescriptor toSuiteDescriptor(Description description) {
    Class<?> testClass = description.getTestClass();
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    // relying exclusively on class name: some runners (such as PowerMock) may redefine test classes
    return new TestSuiteDescriptor(testSuiteName, null);
  }

  /**
   * Is JUnit 5 test that is executed with JUnit 4
   * using @RunWith(org.junit.platform.runner.JUnitPlatform.class)
   */
  public static boolean isJUnitPlatformRunnerTest(Description description) {
    Object uniqueId = getUniqueId(description);
    return uniqueId != null && uniqueId.toString().contains("[engine:");
  }
}
