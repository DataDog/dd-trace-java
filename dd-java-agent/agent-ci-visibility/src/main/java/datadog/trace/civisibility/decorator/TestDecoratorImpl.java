package datadog.trace.civisibility.decorator;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.civisibility.config.JvmInfo;
import java.util.Map;

public class TestDecoratorImpl implements TestDecorator {

  private static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

  private final String component;
  private final Map<String, String> ciTags;

  public TestDecoratorImpl(String component, Map<String, String> ciTags) {
    this.component = component;
    this.ciTags = ciTags;
  }

  protected String testType() {
    return TEST_TYPE;
  }

  protected String runtimeName() {
    return JvmInfo.CURRENT_JVM.getName();
  }

  protected String runtimeVendor() {
    return JvmInfo.CURRENT_JVM.getVendor();
  }

  protected String runtimeVersion() {
    return JvmInfo.CURRENT_JVM.getVersion();
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
  public CharSequence component() {
    return component;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    /*
     * IMPORTANT: JVM and OS tags should match properties
     * set in datadog.trace.civisibility.config.ModuleExecutionSettingsFactory
     *
     * Moreover, logic that populates these tags cannot be changed,
     * as they are used to establish correspondence between
     * executions of the same test case
     */
    span.setTag(Tags.TEST_TYPE, testType());
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    span.setTag(Tags.RUNTIME_NAME, runtimeName());
    span.setTag(Tags.RUNTIME_VENDOR, runtimeVendor());
    span.setTag(Tags.RUNTIME_VERSION, runtimeVersion());
    span.setTag(Tags.OS_ARCHITECTURE, osArch());
    span.setTag(Tags.OS_PLATFORM, osPlatform());
    span.setTag(Tags.OS_VERSION, osVersion());
    span.setTag(DDTags.ORIGIN_KEY, CIAPP_TEST_ORIGIN);
    span.setTag(Tags.COMPONENT, component());

    for (final Map.Entry<String, String> ciTag : ciTags.entrySet()) {
      span.setTag(ciTag.getKey(), ciTag.getValue());
    }

    return span;
  }
}
