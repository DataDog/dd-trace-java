package datadog.trace.civisibility.decorator;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Strings;
import java.util.Map;

public class TestDecoratorImpl implements TestDecorator {

  private static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

  private final String component;
  private final String sessionName;
  private final Map<String, String> ciTags;
  private final int cpuCount;

  public TestDecoratorImpl(
      String component, String sessionName, String testCommand, Map<String, String> ciTags) {
    this.component = component;
    this.ciTags = ciTags;
    if (Strings.isNotBlank(sessionName)) {
      this.sessionName = sessionName;
    } else {
      String ciJobName = ciTags.get(Tags.CI_JOB_NAME);
      this.sessionName =
          Strings.isNotBlank(ciJobName) ? ciJobName + "-" + testCommand : testCommand;
    }
    cpuCount = Runtime.getRuntime().availableProcessors();
  }

  protected String testType() {
    return TEST_TYPE;
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
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    span.setTag(DDTags.ORIGIN_KEY, CIAPP_TEST_ORIGIN);
    span.setTag(DDTags.HOST_VCPU_COUNT, cpuCount);
    span.setTag(Tags.TEST_TYPE, testType());
    span.setTag(Tags.COMPONENT, component());
    span.context().setIntegrationName(component());
    span.setTag(Tags.TEST_SESSION_NAME, sessionName);

    for (final Map.Entry<String, String> ciTag : ciTags.entrySet()) {
      span.setTag(ciTag.getKey(), ciTag.getValue());
    }

    return span;
  }
}
