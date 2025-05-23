package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.Tags

class ClientDecoratorTest extends BaseDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator((String) serviceName)
    def spanContext = Mock(AgentSpanContext)

    when:
    decorator.afterStart(span)

    then:
    if (serviceName != null) {
      1 * span.setServiceName(serviceName)
    }
    1 * span.setMeasured(true)
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.context() >> spanContext
    1 * spanContext.setIntegrationName("test-component")
    1 * span.setTag(Tags.SPAN_KIND, "client")
    1 * span.setSpanType(decorator.spanType())
    1 * span.setMetric(DDTags.ANALYTICS_SAMPLE_RATE, 1.0)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test beforeFinish"() {
    when:
    newDecorator("test-service").beforeFinish(span)

    then:
    0 * _
  }

  @Override
  def newDecorator() {
    return newDecorator("test-service")
  }

  def newDecorator(String serviceName) {
    return new ClientDecorator() {
        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
        }

        @Override
        protected String service() {
          return serviceName
        }

        @Override
        protected CharSequence spanType() {
          return "test-type"
        }

        @Override
        protected CharSequence component() {
          return "test-component"
        }

        protected boolean traceAnalyticsDefault() {
          return true
        }
      }
  }
}
