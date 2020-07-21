package datadog.trace.core.processor

import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.SpanFactory
import datadog.trace.core.interceptor.TraceHeuristicsEvaluator
import datadog.trace.core.processor.rule.URLAsResourceNameRule
import datadog.trace.util.test.DDSpecification
import spock.lang.Subject

class TraceProcessorTest extends DDSpecification {

  def traceHeuristicsEvaluator = Mock(TraceHeuristicsEvaluator)

  @Subject
  def processor = new TraceProcessor(traceHeuristicsEvaluator)

  def span = SpanFactory.newSpanOf(0)
  def trace = [span]

  def setup() {
    span.context.resourceName = null
  }

  def "test disable"() {
    setup:
    ConfigUtils.updateConfig {
      System.setProperty("dd.trace.${name}.enabled", "false")
    }
    def processor = new TraceProcessor(traceHeuristicsEvaluator)

    expect:
    !processor.rules.any {
      it.class.name == rule.name
    }

    cleanup:
    ConfigUtils.updateConfig {
      System.clearProperty("dd.trace.${name}.enabled")
    }

    where:
    rule                  | alias
    URLAsResourceNameRule | null
    URLAsResourceNameRule | URLAsResourceNameRule.simpleName.toLowerCase()
    URLAsResourceNameRule | "URLAsResourceName"
    URLAsResourceNameRule | "Status404Rule"
    URLAsResourceNameRule | "Status404Decorator"

    name = alias == null ? rule.simpleName : alias
  }

  def "set 404 as a resource on a 404 issue"() {
    setup:
    span.setTag(Tags.HTTP_STATUS, 404)

    when:
    processor.onTraceComplete(trace)

    then:
    span.getResourceName() == "404"
  }

  def "#type status #status code error=#error"() {
    setup:
    if (type) {
      span.setSpanType(DDSpanTypes."$type")
    } else {
      span.setSpanType(null)
    }
    span.setTag(Tags.HTTP_STATUS, status)

    when:
    processor.onTraceComplete(trace)

    then:
    span.isError() == error

    where:
    type          | status | error
    null          | 400    | false
    null          | 500    | false
    "HTTP_CLIENT" | 400    | true
    "HTTP_CLIENT" | 404    | true
    "HTTP_CLIENT" | 499    | true
    "HTTP_CLIENT" | 500    | false
    "HTTP_CLIENT" | 550    | false
    "HTTP_CLIENT" | 599    | false
    "HTTP_CLIENT" | 600    | false
    "HTTP_SERVER" | 400    | false
    "HTTP_SERVER" | 404    | false
    "HTTP_SERVER" | 499    | false
    "HTTP_SERVER" | 500    | true
    "HTTP_SERVER" | 550    | true
    "HTTP_SERVER" | 599    | true
    "HTTP_SERVER" | 600    | false
  }

  def "resource name set with url path #url to #resourceName"() {
    setup:
    if (method) {
      span.setTag(Tags.HTTP_METHOD, method)
    }
    span.setTag(Tags.HTTP_URL, url)
    span.setTag(Tags.HTTP_STATUS, status)

    when:
    processor.onTraceComplete(trace)

    then:
    span.resourceName == resourceName

    where:
    method | url      | status | resourceName
    "GET"  | ""       | 200    | "fakeOperation"
    null   | "/"      | 200    | "/"
    null   | "/path"  | 200    | "/path"
    "put"  | "/"      | 200    | "PUT /"
    "Head" | "/path"  | 200    | "HEAD /path"
    "post" | "/post"  | 400    | "POST /post"
    "GET"  | "/asdf"  | 404    | "404"
    null   | "/error" | 500    | "/error"
  }

  def "convert _dd.measured to metric"() {
    setup:
    span.setTag(InstrumentationTags.DD_MEASURED, true)

    when:
    processor.onTraceComplete(trace)

    then:
    span.metrics.get(InstrumentationTags.DD_MEASURED) == 1
    span.tags.get(InstrumentationTags.DD_MEASURED) == null
  }
}
