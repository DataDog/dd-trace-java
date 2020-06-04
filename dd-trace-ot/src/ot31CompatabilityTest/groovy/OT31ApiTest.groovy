import datadog.opentracing.DDTracer
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.util.test.DDSpecification
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMap
import spock.lang.Subject

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

// This test focuses on things that are different between OpenTracing API 0.31.0 and 0.32.0
class OT31ApiTest extends DDSpecification {
  static final WRITER = new ListWriter()

  @Subject
  Tracer tracer = DDTracer.builder().writer(WRITER).build()

  def "test startActive"() {
    when:
    def scope = tracer.buildSpan("some name").startActive(finishSpan)
    scope.close()

    then:
    (scope.span().delegate as DDSpan).isFinished() == finishSpan

    where:
    finishSpan << [true, false]
  }

  def "test startManual"() {
    when:
    tracer.buildSpan("some name").startManual().finish()

    then:
    assertTraces(WRITER, 1) {
      trace(0, 1) {
        basicSpan(it, 0, "some name")
      }
    }
  }

  def "test scopemanager"() {
    setup:
    def span = tracer.buildSpan("some name").start()
    def scope = tracer.scopeManager().activate(span, finishSpan)

    expect:
    scope != null
    tracer.scopeManager().active().span() == span

    when: "attempting to close the span this way doesn't work because we lost the 'finishSpan' reference"
    tracer.scopeManager().active().close()

    then:
    !(span.delegate as DDSpan).isFinished()

    when:
    scope.close()

    then:
    (span.delegate as DDSpan).isFinished() == finishSpan

    where:
    finishSpan << [true, false]
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
      "x-datadog-trace-id"         : context.toTraceId(),
      "x-datadog-parent-id"        : context.toSpanId(),
      "x-datadog-sampling-priority": "$context.delegate.samplingPriority",
    ]

    when:
    def extract = tracer.extract(Format.Builtin.TEXT_MAP, adapter)

    then:
    extract.toTraceId() == context.toTraceId()
    extract.toSpanId() == context.toSpanId()
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
