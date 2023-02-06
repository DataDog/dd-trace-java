package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.source.MethodLinesResolver;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TestDecorator extends BaseDecorator {

  private static final Logger log = LoggerFactory.getLogger(TestDecorator.class);

  public static final String TEST_TYPE = "test";
  public static final String TEST_PASS = "pass";
  public static final String TEST_FAIL = "fail";
  public static final String TEST_SKIP = "skip";
  public static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

  private final ConcurrentMap<TestSuiteDescriptor, AgentSpan> activeTestSuites =
      new ConcurrentHashMap<>();

  public boolean isCI() {
    return InstrumentationBridge.isCi();
  }

  public Map<String, String> getCiTags() {
    return InstrumentationBridge.getCiTags();
  }

  protected abstract String testFramework();

  protected String testType() {
    return TEST_TYPE;
  }

  protected String testSpanKind() {
    return Tags.SPAN_KIND_TEST;
  }

  protected String testSuiteSpanKind() {
    return Tags.SPAN_KIND_TEST_SUITE;
  }

  protected String runtimeName() {
    return System.getProperty("java.runtime.name");
  }

  protected String runtimeVendor() {
    return System.getProperty("java.vendor");
  }

  protected String runtimeVersion() {
    return System.getProperty("java.version");
  }

  protected String osArch() {
    return System.getProperty("os.arch");
  }

  protected String osPlatform() {
    return System.getProperty("os.name");
  }

  protected String osVersion() {
    return System.getProperty("os.version");
  }

  protected UTF8BytesString origin() {
    return CIAPP_TEST_ORIGIN;
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(Tags.TEST_FRAMEWORK, testFramework());
    span.setTag(Tags.TEST_TYPE, testType());
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    span.setTag(Tags.RUNTIME_NAME, runtimeName());
    span.setTag(Tags.RUNTIME_VENDOR, runtimeVendor());
    span.setTag(Tags.RUNTIME_VERSION, runtimeVersion());
    span.setTag(Tags.OS_ARCHITECTURE, osArch());
    span.setTag(Tags.OS_PLATFORM, osPlatform());
    span.setTag(Tags.OS_VERSION, osVersion());
    span.setTag(DDTags.ORIGIN_KEY, CIAPP_TEST_ORIGIN);

    Map<String, String> ciTags = InstrumentationBridge.getCiTags();
    for (final Map.Entry<String, String> ciTag : ciTags.entrySet()) {
      span.setTag(ciTag.getKey(), ciTag.getValue());
    }

    return super.afterStart(span);
  }

  protected void afterTestSuiteStart(
      final AgentSpan span,
      final String testSuiteName,
      final @Nullable String version,
      final @Nullable Class<?> testClass,
      final @Nullable List<String> categories) {
    span.setSpanType(InternalSpanTypes.TEST_SUITE_END);
    span.setTag(Tags.SPAN_KIND, testSuiteSpanKind());

    span.setResourceName(testSuiteName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);

    // default value that may be overwritten later based on statuses of individual test cases
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    // FIXME
    //  test.module
    //  test.bundle -- exactly the same as test.module

    if (categories != null) {
      span.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories))));
    }

    if (testClass != null) {
      if (Config.get().isCiVisibilitySourceDataEnabled()) {
        SourcePathResolver sourcePathResolver = InstrumentationBridge.getSourcePathResolver();
        String sourcePath = sourcePathResolver.getSourcePath(testClass);
        if (sourcePath != null && !sourcePath.isEmpty()) {
          span.setTag(Tags.TEST_SOURCE_FILE, sourcePath);
        }
      }
    }

    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    activeTestSuites.put(testSuiteDescriptor, span);

    afterStart(span);
  }

  protected void beforeTestSuiteFinish(
      AgentSpan span, final String testSuiteName, final @Nullable Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    activeTestSuites.remove(testSuiteDescriptor);

    span.setTag(Tags.TEST_SUITE_ID, span.getSpanId());

    beforeFinish(span);
  }

  protected void afterTestStart(
      final AgentSpan span,
      final String testSuiteName,
      final String testName,
      final @Nullable String testParameters,
      final @Nullable String version,
      final @Nullable Class<?> testClass,
      final @Nullable Method testMethod,
      final @Nullable List<String> categories) {

    span.setSpanType(InternalSpanTypes.TEST);
    span.setTag(Tags.SPAN_KIND, testSpanKind());

    span.setResourceName(testSuiteName + "." + testName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_NAME, testName);

    if (testParameters != null) {
      span.setTag(Tags.TEST_PARAMETERS, testParameters);
    }

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    if (categories != null) {
      span.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories))));
    }

    if (Config.get().isCiVisibilitySourceDataEnabled()) {
      populateSourceDataTags(span, testClass, testMethod);
    }

    afterStart(span);
  }

  private void populateSourceDataTags(AgentSpan span, Class<?> testClass, Method testMethod) {
    if (testClass == null) {
      return;
    }

    SourcePathResolver sourcePathResolver = InstrumentationBridge.getSourcePathResolver();
    String sourcePath = sourcePathResolver.getSourcePath(testClass);
    if (sourcePath == null || sourcePath.isEmpty()) {
      return;
    }

    span.setTag(Tags.TEST_SOURCE_FILE, sourcePath);

    if (testMethod != null) {
      MethodLinesResolver methodLinesResolver = InstrumentationBridge.getMethodLinesResolver();
      MethodLinesResolver.MethodLines testMethodLines = methodLinesResolver.getLines(testMethod);
      if (testMethodLines.isValid()) {
        span.setTag(Tags.TEST_SOURCE_START, testMethodLines.getStartLineNumber());
        span.setTag(Tags.TEST_SOURCE_END, testMethodLines.getFinishLineNumber());
      }
    }

    Codeowners codeowners = InstrumentationBridge.getCodeowners();
    Collection<String> testCodeOwners = codeowners.getOwners(sourcePath);
    if (testCodeOwners != null) {
      span.setTag(Tags.TEST_CODEOWNERS, toJson(testCodeOwners));
    }
  }

  protected void beforeTestFinish(
      AgentSpan span, String testSuiteName, @Nullable Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    AgentSpan testSuiteSpan = activeTestSuites.get(testSuiteDescriptor);
    if (testSuiteSpan != null) {
      long testSuiteSpanId = testSuiteSpan.getSpanId();
      span.setTag(Tags.TEST_SUITE_ID, testSuiteSpanId);

      String testSuiteStatus = (String) testSuiteSpan.getTag(Tags.TEST_STATUS);
      String testCaseStatus = (String) span.getTag(Tags.TEST_STATUS);
      switch (testCaseStatus) {
        case TEST_SKIP:
          // a test suite will have SKIP status by default
          break;
        case TEST_PASS:
          if (!TEST_FAIL.equals(testSuiteStatus)) {
            // if at least one test case passes (i.e. is not skipped) and no test cases fail,
            // test suite status should be PASS
            testSuiteSpan.setTag(Tags.TEST_STATUS, TEST_PASS);
          }
          break;
        case TEST_FAIL:
          // if at least one test case fails, test suite status should be FAIL
          testSuiteSpan.setTag(Tags.TEST_STATUS, TEST_FAIL);
          break;
        default:
          log.debug(
              "Unexpected test case status {} for test case with ID {}",
              testCaseStatus,
              span.getSpanId());
          break;
      }

    } else {
      // TODO log a warning (when test suite level visibility support is there for all frameworks
    }

    beforeFinish(span);
  }

  public List<Method> testMethods(
      final Class<?> testClass, final Class<? extends Annotation> testAnnotation) {
    final List<Method> testMethods = new ArrayList<>();

    final Method[] methods = testClass.getMethods();
    for (final Method method : methods) {
      if (method.getAnnotation(testAnnotation) != null) {
        testMethods.add(method);
      }
    }
    return testMethods;
  }

  public boolean isTestSpan(@Nullable final AgentSpan activeSpan) {
    if (activeSpan == null) {
      return false;
    }

    return DDSpanTypes.TEST.equals(activeSpan.getSpanType())
        && testType().equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  public boolean isTestSuiteSpan(@Nullable final AgentSpan activeSpan) {
    if (activeSpan == null) {
      return false;
    }

    return DDSpanTypes.TEST_SUITE_END.equals(activeSpan.getSpanType())
        && testType().equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  private static final class TestSuiteDescriptor {
    private final String testSuiteName;
    private final Class<?> testClass;

    private TestSuiteDescriptor(String testSuiteName, Class<?> testClass) {
      this.testSuiteName = testSuiteName;
      this.testClass = testClass;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TestSuiteDescriptor that = (TestSuiteDescriptor) o;
      return Objects.equals(testSuiteName, that.testSuiteName)
          && Objects.equals(testClass, that.testClass);
    }

    @Override
    public int hashCode() {
      return Objects.hash(testSuiteName, testClass);
    }
  }
}
