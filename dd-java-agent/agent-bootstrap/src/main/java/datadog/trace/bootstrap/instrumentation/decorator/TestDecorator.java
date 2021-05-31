package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.ci.CIProviderInfo;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TestDecorator extends BaseDecorator {

  private static final Logger log = LoggerFactory.getLogger(TestDecorator.class);

  public static final String TEST_TYPE = "test";
  public static final String TEST_PASS = "pass";
  public static final String TEST_FAIL = "fail";
  public static final String TEST_SKIP = "skip";
  public static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

  private final boolean isCI;
  private final Map<String, String> ciTags;

  public TestDecorator() {
    this(CIProviderInfo.selectCI());
  }

  TestDecorator(final CIProviderInfo ciInfo) {
    this.isCI = ciInfo.isCI();
    this.ciTags = ciInfo.getCiTags();
  }

  public boolean isCI() {
    return isCI;
  }

  public Map<String, String> getCiTags() {
    return ciTags;
  }

  protected abstract String testFramework();

  protected String testType() {
    return TEST_TYPE;
  }

  protected String spanKind() {
    return Tags.SPAN_KIND_TEST;
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
    return DDSpanTypes.TEST;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(Tags.SPAN_KIND, spanKind());
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

    for (final Map.Entry<String, String> ciTag : ciTags.entrySet()) {
      span.setTag(ciTag.getKey(), ciTag.getValue());
    }

    return super.afterStart(span);
  }

  public List<String> testNames(
      final Class<?> testClass, final Class<? extends Annotation> testAnnotation) {
    final List<String> testNames = new ArrayList<>();

    final Method[] methods = testClass.getMethods();
    for (final Method method : methods) {
      if (method.getAnnotation(testAnnotation) != null) {
        testNames.add(method.getName());
      }
    }
    return testNames;
  }

  public boolean isTestSpan(final AgentSpan activeSpan) {
    if (activeSpan == null) {
      return false;
    }

    return spanKind().equals(activeSpan.getSpanType())
        && testType().equals(activeSpan.getTag(Tags.TEST_TYPE));
  }
}
