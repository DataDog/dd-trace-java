package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.util.Strings;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import junit.runner.Version;
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

  public static final String JUNIT_4_FRAMEWORK = "junit4";
  private static final String JUNIT_VERSION = Version.id();
  private static final MethodHandle PARENT_RUNNER_DESCRIBE_CHILD;
  private static final MethodHandle RUN_NOTIFIER_LISTENERS;
  private static final MethodHandle INNER_SYNCHRONIZED_LISTENER;
  private static final MethodHandle DESCRIPTION_UNIQUE_ID;
  private static final MethodHandle CREATE_DESCRIPTION_WITH_UNIQUE_ID;
  public static final boolean NATIVE_SUITE_EVENTS_SUPPORTED;

  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    PARENT_RUNNER_DESCRIBE_CHILD = accessDescribeChildMethodInParentRunner(lookup);
    RUN_NOTIFIER_LISTENERS = accessListenersFieldInRunNotifier(lookup);
    INNER_SYNCHRONIZED_LISTENER = accessListenerFieldInSynchronizedListener(lookup);
    DESCRIPTION_UNIQUE_ID = accessUniqueIdInDescription(lookup);
    CREATE_DESCRIPTION_WITH_UNIQUE_ID = accessCreateDescriptionWithUniqueId(lookup);
    NATIVE_SUITE_EVENTS_SUPPORTED = nativeSuiteEventsSupported();
  }

  /** JUnit 4 support test suite started/finished events in versions 4.13 and later */
  private static boolean nativeSuiteEventsSupported() {
    try {
      RunListener.class.getDeclaredMethod("testSuiteStarted", Description.class);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static MethodHandle accessDescribeChildMethodInParentRunner(MethodHandles.Lookup lookup) {
    try {
      Method describeChild = ParentRunner.class.getDeclaredMethod("describeChild", Object.class);
      describeChild.setAccessible(true);
      return lookup.unreflect(describeChild);
    } catch (Exception e) {
      return null;
    }
  }

  private static MethodHandle accessListenersFieldInRunNotifier(MethodHandles.Lookup lookup) {
    try {
      Field listeners;
      try {
        // Since JUnit 4.12, the field is called "listeners"
        listeners = RunNotifier.class.getDeclaredField("listeners");
      } catch (final NoSuchFieldException e) {
        // Before JUnit 4.12, the field is called "fListeners"
        listeners = RunNotifier.class.getDeclaredField("fListeners");
      }

      listeners.setAccessible(true);
      return lookup.unreflectGetter(listeners);
    } catch (final Throwable e) {
      log.debug("Could not get runListeners for JUnit4Advice", e);
      return null;
    }
  }

  private static MethodHandle accessListenerFieldInSynchronizedListener(
      MethodHandles.Lookup lookup) {
    ClassLoader classLoader = RunListener.class.getClassLoader();
    MethodHandle handle = accessListenerFieldInSynchronizedListener(lookup, classLoader);
    if (handle != null) {
      return handle;
    } else {
      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      return accessListenerFieldInSynchronizedListener(lookup, contextClassLoader);
    }
  }

  private static MethodHandle accessListenerFieldInSynchronizedListener(
      MethodHandles.Lookup lookup, ClassLoader classLoader) {
    try {
      Class<?> synchronizedListenerClass = classLoader.loadClass(SYNCHRONIZED_LISTENER);
      final Field innerListenerField = synchronizedListenerClass.getDeclaredField("listener");
      innerListenerField.setAccessible(true);
      return lookup.unreflectGetter(innerListenerField);
    } catch (Exception e) {
      return null;
    }
  }

  private static MethodHandle accessUniqueIdInDescription(MethodHandles.Lookup lookup) {
    try {
      final Field uniqueIdField = Description.class.getDeclaredField("fUniqueId");
      uniqueIdField.setAccessible(true);
      return lookup.unreflectGetter(uniqueIdField);
    } catch (Throwable throwable) {
      return null;
    }
  }

  private static MethodHandle accessCreateDescriptionWithUniqueId(MethodHandles.Lookup lookup) {
    try {
      Method createDescription =
          Description.class.getDeclaredMethod(
              "createSuiteDescription", String.class, Serializable.class, Annotation[].class);
      return lookup.unreflect(createDescription);
    } catch (Throwable throwable) {
      return null;
    }
  }

  public static List<RunListener> runListenersFromRunNotifier(final RunNotifier runNotifier) {
    try {
      if (RUN_NOTIFIER_LISTENERS != null) {
        return (List<RunListener>) RUN_NOTIFIER_LISTENERS.invoke(runNotifier);
      }
    } catch (final Throwable e) {
      log.debug("Could not get runListeners for JUnit4Advice", e);
    }
    return null;
  }

  public static TracingListener toTracingListener(final RunListener listener) {
    if (listener instanceof TracingListener) {
      return (TracingListener) listener;
    }

    // Since JUnit 4.12, the RunListener are wrapped by a SynchronizedRunListener object.
    if (SYNCHRONIZED_LISTENER.equals(listener.getClass().getName())) {
      try {
        if (INNER_SYNCHRONIZED_LISTENER != null) {
          Object innerListener = INNER_SYNCHRONIZED_LISTENER.invoke(listener);
          if (innerListener instanceof TracingListener) {
            return (TracingListener) innerListener;
          }
        }
      } catch (final Throwable e) {
        log.debug("Could not get inner listener from SynchronizedRunListener", e);
      }
    }
    return null;
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
        if (INNER_SYNCHRONIZED_LISTENER != null) {
          Object innerListener = INNER_SYNCHRONIZED_LISTENER.invoke(listener);
          if (innerListener instanceof TracingListener) {
            return (TracingListener) innerListener;
          }
        }
      } catch (final Throwable e) {
        log.debug("Could not get inner listener from SynchronizedRunListener", e);
      }
    }
    return listener;
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

  public static List<String> getCategories(
      String testFramework,
      Description description,
      Class<?> testClass,
      @Nullable Method testMethod) {
    if (Munit.FRAMEWORK.equals(testFramework)) {
      return Munit.getCategories(description);
    }

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
    if (testClass != null) {
      for (Description child : description.getChildren()) {
        if (isTestCaseDescription(child)) {
          return true;
        }
      }

      // this is needed to handle parameterized tests,
      // as they have an extra level of descriptions
      // between the suite and the test cases
      for (Method method : testClass.getMethods()) {
        if (method.getAnnotation(Test.class) != null) {
          return true;
        }
      }
    }

    // check if this is a Cucumber feature
    Object uniqueId = getUniqueId(description);
    return uniqueId != null && uniqueId.toString().endsWith(".feature");
  }

  private static Object getUniqueId(final Description description) {
    try {
      if (DESCRIPTION_UNIQUE_ID != null) {
        return DESCRIPTION_UNIQUE_ID.invoke(description);
      }
    } catch (Throwable e) {
      log.error("Could not get unique ID from descriptions: " + description, e);
    }
    return null;
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

  public static Description getDescription(ParentRunner runner, Object child) {
    try {
      if (PARENT_RUNNER_DESCRIBE_CHILD != null) {
        return (Description) PARENT_RUNNER_DESCRIBE_CHILD.invoke(runner, child);
      }
    } catch (Throwable e) {
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

    Class<?> testClass = description.getTestClass();
    if (testClass != null) {
      Matcher matcher = METHOD_AND_CLASS_NAME_PATTERN.matcher(displayName);
      String name = matcher.matches() ? matcher.group(1) : getTestName(description, null);
      return Description.createTestDescription(testClass, name, updatedAnnotations);

    } else {
      // Cucumber
      if (CREATE_DESCRIPTION_WITH_UNIQUE_ID != null) {
        // Try to preserve unique ID
        // since we use it to determine framework.
        // The factory method that accepts unique ID
        // is only available in JUnit 4.12+
        try {
          Object uniqueId = getUniqueId(description);
          return (Description)
              CREATE_DESCRIPTION_WITH_UNIQUE_ID.invoke(displayName, uniqueId, updatedAnnotations);
        } catch (Throwable throwable) {
          // ignored
        }
      }
      return Description.createSuiteDescription(displayName, updatedAnnotations);
    }
  }

  public static SkippableTest toSkippableTest(Description description) {
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String suite = description.getClassName();
    String name = JUnit4Utils.getTestName(description, testMethod);
    String parameters = JUnit4Utils.getParameters(description);
    return new SkippableTest(suite, name, parameters, null);
  }

  public static String getTestFramework(final Description description) {
    Class<?> testClass = description.getTestClass();
    while (testClass != null) {
      if (testClass.getName().startsWith("munit.")) {
        return Munit.FRAMEWORK;
      }
      testClass = testClass.getSuperclass();
    }

    Object uniqueId = JUnit4Utils.getUniqueId(description);
    if (uniqueId != null && uniqueId.getClass().getName().startsWith("io.cucumber.")) {
      return Cucumber.FRAMEWORK;
    } else {
      return JUNIT_4_FRAMEWORK;
    }
  }

  public static String getTestFrameworkVersion(String testFramework, Description description) {
    switch (testFramework) {
      case Cucumber.FRAMEWORK:
        return Cucumber.getVersion(description);
      case Munit.FRAMEWORK:
        return Munit.getFrameworkVersion(description);
      default:
        return JUNIT_VERSION;
    }
  }

  public static final class Cucumber {
    public static final String FRAMEWORK = "cucumber";
    private static volatile String VERSION;

    private static String getVersion(Description description) {
      if (VERSION == null) {
        String version = null;
        Object cucumberId = JUnit4Utils.getUniqueId(description);
        try (InputStream cucumberPropsStream =
            cucumberId
                .getClass()
                .getClassLoader()
                .getResourceAsStream("META-INF/maven/io.cucumber/cucumber-junit/pom.properties")) {
          Properties cucumberProps = new Properties();
          cucumberProps.load(cucumberPropsStream);
          version = cucumberProps.getProperty("version");
        } catch (Exception e) {
          // fallback below
        }
        if (version != null) {
          VERSION = version;
        } else {
          // Shouldn't happen normally.
          // Put here as a guard against running the code above for every test case
          // if version cannot be determined for whatever reason
          VERSION = "unknown";
        }
      }
      return VERSION;
    }
  }

  public static final class Munit {

    public static final String FRAMEWORK = "munit";
    private static volatile String VERSION;
    private static final Class<Annotation> MUNIT_TAG_ANNOTATION;
    private static final MethodHandle MUNIT_TAG;

    static {
      /*
       * Spock's classes are accessed via reflection and method handles
       * since they are loaded by a different classloader in some envs
       */
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      MUNIT_TAG_ANNOTATION = accessMunitTagAnnotation();
      MUNIT_TAG = accessMunitTag(lookup, MUNIT_TAG_ANNOTATION);
    }

    private static Class<Annotation> accessMunitTagAnnotation() {
      try {
        return (Class<Annotation>) ClassLoader.getSystemClassLoader().loadClass("munit.Tag");
      } catch (Throwable e) {
        return null;
      }
    }

    private static MethodHandle accessMunitTag(
        MethodHandles.Lookup lookup, Class<Annotation> munitTagAnnotation) {
      if (munitTagAnnotation == null) {
        return null;
      }
      try {
        MethodType returnsString = MethodType.methodType(String.class);
        return lookup.findVirtual(munitTagAnnotation, "value", returnsString);
      } catch (Throwable e) {
        return null;
      }
    }

    public static String getFrameworkVersion(Description description) {
      if (VERSION == null) {
        Class<?> testClass = description.getTestClass();
        while (testClass != null) {
          if (testClass.getName().startsWith("munit.")) {
            Package munitPackage = testClass.getPackage();
            VERSION = munitPackage.getImplementationVersion();
            break;
          }
          testClass = testClass.getSuperclass();
        }
      }
      return VERSION;
    }

    private static List<String> getCategories(Description description) {
      List<String> categories = new ArrayList<>();
      try {
        for (Annotation annotation : description.getAnnotations()) {
          Class<? extends Annotation> annotationType = annotation.annotationType();
          if ("munit.Tag".equals(annotationType.getName())) {
            String category = (String) MUNIT_TAG.invoke(annotation);
            categories.add(category);
          }
        }
      } catch (Throwable e) {
        // ignore
      }
      return categories;
    }
  }
}
