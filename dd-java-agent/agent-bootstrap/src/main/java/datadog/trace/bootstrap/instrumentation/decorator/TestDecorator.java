package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.Config;
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
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;

public abstract class TestDecorator extends BaseDecorator {

  public static final String TEST_TYPE = "test";

  private static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

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

  protected String testModuleSpanKind() {
    return Tags.SPAN_KIND_TEST_MODULE;
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
  public CharSequence component() {
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

  public void afterTestModuleStart(final AgentSpan span, final @Nullable String version) {
    span.setSpanType(InternalSpanTypes.TEST_MODULE_END);
    span.setTag(Tags.SPAN_KIND, testModuleSpanKind());

    span.setResourceName(InstrumentationBridge.getModule());
    span.setTag(Tags.TEST_MODULE, InstrumentationBridge.getModule());
    span.setTag(Tags.TEST_BUNDLE, InstrumentationBridge.getModule());

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    afterStart(span);
  }

  public void afterTestSuiteStart(
      final AgentSpan span,
      final String testSuiteName,
      final @Nullable Class<?> testClass,
      final @Nullable String version,
      final @Nullable Collection<String> categories) {
    span.setSpanType(InternalSpanTypes.TEST_SUITE_END);
    span.setTag(Tags.SPAN_KIND, testSuiteSpanKind());

    span.setResourceName(testSuiteName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, InstrumentationBridge.getModule());
    span.setTag(Tags.TEST_BUNDLE, InstrumentationBridge.getModule());

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    if (categories != null && !categories.isEmpty()) {
      span.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories)), true));
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

    afterStart(span);
  }

  public void afterTestStart(
      final AgentSpan span,
      final String testSuiteName,
      final String testName,
      final @Nullable String testParameters,
      final @Nullable String version,
      final @Nullable Class<?> testClass,
      final @Nullable Method testMethod,
      final @Nullable Collection<String> categories) {

    span.setSpanType(InternalSpanTypes.TEST);
    span.setTag(Tags.SPAN_KIND, testSpanKind());

    span.setResourceName(testSuiteName + "." + testName);
    span.setTag(Tags.TEST_NAME, testName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, InstrumentationBridge.getModule());
    span.setTag(Tags.TEST_BUNDLE, InstrumentationBridge.getModule());

    if (testParameters != null) {
      span.setTag(Tags.TEST_PARAMETERS, testParameters);
    }

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    if (categories != null && !categories.isEmpty()) {
      span.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories)), true));
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
}
