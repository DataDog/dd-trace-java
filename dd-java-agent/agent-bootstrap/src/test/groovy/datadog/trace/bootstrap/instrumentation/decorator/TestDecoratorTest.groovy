package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.ci.CIProviderInfo

class TestDecoratorTest extends BaseDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
    decorator.ciTags.each {
      1 * span.setTag(it.key, it.value)
    }
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test beforeFinish"() {
    when:
    newDecorator().beforeFinish(span)

    then:
    0 * _
  }

  @Override
  def newDecorator() {
    return new TestDecorator(newMockCiInfo()) {
      @Override
      protected String testFramework() {
        return "test-framework"
      }

      @Override
      protected String[] instrumentationNames() {
        return ["test1", "test2"]
      }

      @Override
      protected CharSequence spanType() {
        return "test-type"
      }

      @Override
      protected String spanKind() {
        return "test-type"
      }

      @Override
      protected CharSequence component() {
        return "test-component"
      }
    }
  }

  def newMockCiInfo() {
    return new CIProviderInfo() {
      @Override
      Map<String, String> getCiTags() {
        def mockCiTags = new HashMap()
        mockCiTags.put("sample-ci-key", "sample-ci-value")
        return mockCiTags
      }
    }
  }
}
