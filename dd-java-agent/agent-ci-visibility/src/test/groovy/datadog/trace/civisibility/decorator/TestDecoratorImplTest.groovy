package datadog.trace.civisibility.decorator

import datadog.trace.api.DDTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Specification

class TestDecoratorImplTest extends Specification {

  def span = Mock(AgentSpan)
  def context  = Mock(AgentSpanContext)


  def "test afterStart"() {
    setup:
    def decorator = new TestDecoratorImpl("test-component", "session-name", "test-command", ["ci-tag-1": "value", "ci-tag-2": "another value"])
    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.TEST_SESSION_NAME, "session-name")
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.context() >> context
    1 * context.setIntegrationName("test-component")
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
    1 * span.setTag(DDTags.ORIGIN_KEY, decorator.origin())
    1 * span.setTag(DDTags.HOST_VCPU_COUNT, Runtime.runtime.availableProcessors())
    1 * span.setTag("ci-tag-1", "value")
    1 * span.setTag("ci-tag-2", "another value")

    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test session name: #sessionName, #ciJobName, #testCommand"() {
    setup:
    def decorator = new TestDecoratorImpl("test-component", sessionName, testCommand, [(Tags.CI_JOB_NAME): ciJobName])

    when:
    decorator.afterStart(span)

    then:
    1 * span.context() >> context
    1 * context.setIntegrationName("test-component")
    1 * span.setTag(Tags.TEST_SESSION_NAME, expectedSessionName)

    where:
    sessionName    | ciJobName     | testCommand    | expectedSessionName
    "session-name" | "ci-job-name" | "test-command" | "session-name"
    null           | "ci-job-name" | "test-command" | "ci-job-name-test-command"
    null           | null          | "test-command" | "test-command"
  }
}
