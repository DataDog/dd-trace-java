import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.context.TraceScope
import datadog.trace.core.DDSpan
import io.opentracing.log.Fields
import io.opentracing.noop.NoopSpan
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMap
import io.opentracing.util.GlobalTracer
import spock.lang.Subject

class OpenTracing31Test extends AgentTestRunner {

  @Subject
  def tracer = GlobalTracer.get()

  def "test #method"() {
    setup:
    def builder = tracer.buildSpan("some name")
    if (tagBuilder) {
      builder.withTag(DDTags.RESOURCE_NAME, "some resource")
        .withTag("string", "a")
        .withTag("number", 1)
        .withTag("boolean", true)
    }
    def result = builder.start()
    if (tagSpan) {
      result.setTag(DDTags.RESOURCE_NAME, "other resource")
        .setTag("string", "b")
        .setTag("number", 2)
        .setTag("boolean", false)
    }
    if (exception) {
      result.log([(Fields.ERROR_OBJECT): exception])
    }

    expect:
    result instanceof MutableSpan
    (result as MutableSpan).localRootSpan == result.delegate
    (result as MutableSpan).isError() == (exception != null)
    tracer.activeSpan() == null
    result.context().baggageItems().isEmpty()

    when:
    result.setBaggageItem("test", "baggage")

    then:
    result.getBaggageItem("test") == "baggage"
    result.context().baggageItems() == ["test": "baggage"].entrySet()

    when:
    result.finish()

    then:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          parent()
          serviceName "unnamed-java-app"
          operationName "some name"
          if (tagSpan) {
            resourceName "other resource"
          } else if (tagBuilder) {
            resourceName "some resource"
          } else {
            resourceName "some name"
          }
          errored exception != null
          tags {
            if (tagSpan) {
              "string" "b"
              "number" 2
              "boolean" false
            } else if (tagBuilder) {
              "string" "a"
              "number" 1
              "boolean" true
            }
            if (exception) {
              errorTags(exception.class)
            }
            defaultTags()
          }
          metrics {
            defaultMetrics()
          }
        }
      }
    }

    where:
    method        | tagBuilder | tagSpan | exception
    "start"       | true       | false   | null
    "startManual" | true       | true    | new Exception()
    "startManual" | false      | false   | new Exception()
    "start"       | false      | true    | null
  }

  def "test startActive"() {
    setup:
    def scope = tracer.buildSpan("some name").startActive(finishSpan)

    expect:
    scope instanceof TraceScope
    tracer.activeSpan().delegate == scope.span().delegate

    when:
    scope.close()

    then:
    (scope.span().delegate as DDSpan).isFinished() == finishSpan

    where:
    finishSpan << [true, false]
  }

  def "test scopemanager"() {
    setup:
    def span = tracer.buildSpan("some name").start()
    def scope = tracer.scopeManager().activate(span, finishSpan)

    expect:
    span instanceof MutableSpan
    scope instanceof TraceScope
    !(scope as TraceScope).isAsyncPropagating()
    (scope as TraceScope).capture() == null
    (tracer.scopeManager().active().span().delegate == span.delegate)

    when:
    (scope as TraceScope).setAsyncPropagation(true)
    def continuation = (scope as TraceScope).capture()
    continuation.cancel()

    then:
    (scope as TraceScope).isAsyncPropagating()
    continuation instanceof TraceScope.Continuation

    when: "attempting to close the span this way doesn't work because we lost the 'finishSpan' reference"
    tracer.scopeManager().active().close()

    then:
    !(span.delegate as DDSpan).isFinished()

    when:
    scope.close()

    then:
    (span.delegate as DDSpan).isFinished() == finishSpan

    where:
    finishSpan | _
    true       | _
    false      | _
  }

  def "test scopemanager with non OTSpan"() {
    setup:
    def span = NoopSpan.INSTANCE
    def scope = tracer.scopeManager().activate(span, true)

    expect:
    !(span instanceof MutableSpan)
    scope instanceof TraceScope

    and: "non OTSpans aren't supported and get converted to NoopAgentSpan"
    tracer.scopeManager().active().span() != span

    when:
    scope.close()
    scope.span().finish()

    then:
    assertTraces(0) {}
  }

  def "test continuation"() {
    setup:
    def span = tracer.buildSpan("some name").start()
    TraceScope scope = tracer.scopeManager().activate(span, false)
    scope.setAsyncPropagation(true)

    expect:
    tracer.activeSpan().delegate == span.delegate

    when:
    def continuation = scope.capture()

    then:
    continuation instanceof TraceScope.Continuation

    when:
    scope.close()

    then:
    tracer.activeSpan() == null

    when:
    scope = continuation.activate()

    then:
    tracer.activeSpan().delegate == span.delegate

    cleanup:
    scope.close()
  }

  def "test inject extract"() {
    setup:
    def context = tracer.buildSpan("some name").start().context()
    def textMap = [:]
    def adapter = new TextMapAdapter(textMap)

    when:
    tracer.inject(context, Format.Builtin.TEXT_MAP, adapter)

    then:
    textMap == [
      "x-datadog-trace-id"         : "$context.delegate.traceId",
      "x-datadog-parent-id"        : "$context.delegate.spanId",
      "x-datadog-sampling-priority": "$context.delegate.samplingPriority",
    ]

    when:
    def extract = tracer.extract(Format.Builtin.TEXT_MAP, adapter)

    then:
    extract.delegate.traceId == context.delegate.traceId
    extract.delegate.spanId == context.delegate.spanId
    extract.delegate.samplingPriority == context.delegate.samplingPriority
  }

  static class TextMapAdapter implements TextMap {
    private final Map<String, String> map

    TextMapAdapter(Map<String, String> map) {
      this.map = map
    }

    @Override
    Iterator<Map.Entry<String, String>> iterator() {
      return map.entrySet().iterator()
    }

    @Override
    void put(String key, String value) {
      map.put(key, value)
    }
  }
}
