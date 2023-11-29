package datadog.trace.civisibility.decorator

import datadog.trace.api.DDTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Specification

class TestDecoratorImplTest extends Specification {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
    1 * span.setTag(Tags.RUNTIME_NAME, decorator.runtimeName())
    1 * span.setTag(Tags.RUNTIME_VENDOR, decorator.runtimeVendor())
    1 * span.setTag(Tags.RUNTIME_VERSION, decorator.runtimeVersion())
    1 * span.setTag(Tags.OS_ARCHITECTURE, decorator.osArch())
    1 * span.setTag(Tags.OS_PLATFORM, decorator.osPlatform())
    1 * span.setTag(Tags.OS_VERSION, decorator.osVersion())
    1 * span.setTag(DDTags.ORIGIN_KEY, decorator.origin())
    1 * span.setTag("ci-tag-1", "value")
    1 * span.setTag("ci-tag-2", "another value")

    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  static newDecorator() {
    new TestDecoratorImpl("test-component", ["ci-tag-1": "value", "ci-tag-2": "another value"])
  }
}
