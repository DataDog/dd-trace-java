package datadog.trace.bootstrap.instrumentation.decorator


import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND

class ServerDecoratorTest extends BaseDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    def decorator = newDecorator()
    def spanContext = Mock(AgentSpanContext)

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE)
    1 * span.setTag(COMPONENT, "test-component")
    1 * span.context() >> spanContext
    1 * spanContext.setIntegrationName("test-component")
    1 * span.setTag(SPAN_KIND, "server")
    1 * span.setSpanType(decorator.spanType())
    if (decorator.traceAnalyticsEnabled) {
      1 * span.setMetric(ANALYTICS_SAMPLE_RATE, 1.0)
    }
    0 * _
  }

  def "test beforeFinish"() {
    when:
    newDecorator().beforeFinish(span)

    then:
    (0..1) * span.localRootSpan
  }

  @Override
  def newDecorator() {
    return new ServerDecorator() {
        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
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
