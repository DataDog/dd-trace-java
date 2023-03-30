package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;
import javax.annotation.Nullable;

public abstract class AbstractTestDecorator extends BaseDecorator implements TestDecorator {

  private static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

  private final String component;
  private final String testFramework;
  private final String testFrameworkVersion;
  private final Map<String, String> ciTags;

  public AbstractTestDecorator(
      String component,
      String testFramework,
      String testFrameworkVersion,
      Map<String, String> ciTags) {
    this.component = component;
    this.testFramework = testFramework;
    this.testFrameworkVersion = testFrameworkVersion;
    this.ciTags = ciTags;
  }

  protected String testType() {
    return TEST_TYPE;
  }

  protected String testModuleSpanKind() {
    return Tags.SPAN_KIND_TEST_MODULE;
  }

  protected String testSessionSpanKind() {
    return Tags.SPAN_KIND_TEST_SESSION;
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
    return component;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(Tags.TEST_FRAMEWORK, testFramework);
    span.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
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

  @Override
  public void afterTestSessionStart(
      final AgentSpan span,
      final String projectName,
      final String startCommand,
      final String buildSystemName,
      final String buildSystemVersion) {
    span.setSpanType(InternalSpanTypes.TEST_SESSION_END);
    span.setTag(Tags.SPAN_KIND, testSessionSpanKind());

    span.setResourceName(projectName);
    span.setTag(Tags.TEST_COMMAND, startCommand);
    span.setTag(Tags.TEST_TOOLCHAIN, buildSystemName + ":" + buildSystemVersion);

    afterStart(span);
  }

  @Override
  public void afterTestModuleStart(
      final AgentSpan span,
      final @Nullable String moduleName,
      final @Nullable String version,
      final @Nullable String startCommand) {
    span.setSpanType(InternalSpanTypes.TEST_MODULE_END);
    span.setTag(Tags.SPAN_KIND, testModuleSpanKind());

    span.setResourceName(moduleName);
    span.setTag(Tags.TEST_MODULE, moduleName);
    span.setTag(Tags.TEST_COMMAND, startCommand);

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    afterStart(span);
  }
}
