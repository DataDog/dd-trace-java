package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.Config
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags

class ServerDecoratorTest extends BaseDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    def decorator = newDecorator()
    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Config.LANGUAGE_TAG_KEY, Config.LANGUAGE_TAG_VALUE)
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, "server")
    1 * span.setTag(DDTags.SPAN_TYPE, decorator.spanType())
    if (decorator.traceAnalyticsEnabled) {
      1 * span.setTag(DDTags.ANALYTICS_SAMPLE_RATE, 1.0)
    }
    1 * span.setTag("_dd.measured", "1")
    0 * _
  }

  def "test beforeFinish"() {
    when:
    newDecorator().beforeFinish(span)

    then:
    0 * _
  }

  @Override
  def newDecorator() {
    return new ServerDecorator() {
      @Override
      protected String[] instrumentationNames() {
        return ["test1", "test2"]
      }

      @Override
      protected String spanType() {
        return "test-type"
      }

      @Override
      protected String component() {
        return "test-component"
      }

      protected boolean traceAnalyticsDefault() {
        return true
      }
    }
  }
}
