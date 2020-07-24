package datadog.trace.api.writer

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.trace.common.writer.PrintingWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.SpanFactory
import datadog.trace.util.test.DDSpecification

class JsonStringWriterTest extends DDSpecification {

  def tracer = Mock(CoreTracer)
  def sampleTrace = [SpanFactory.newSpanOf(tracer), SpanFactory.newSpanOf(tracer)]

  def adapter = new Moshi.Builder().build().adapter(Types.newParameterizedType(List.class, Map.class))

  def "test printing regular ids"() {
    given:
    def printStream = Mock(PrintStream)
    def writer = new PrintingWriter(printStream, false)

    when:
    writer.write(sampleTrace)

    then:
    1 * printStream.println({
      List<Map> result = adapter.fromJson(it as String)
      result.size() == sampleTrace.size() && result.every { span ->
        return span["service"] == "fakeService" &&
          span["name"] == "fakeOperation" &&
          span["resource"] == "fakeResource" &&
          span["type"] == "fakeType" &&
          span["trace_id"] instanceof Number &&
          span["span_id"] instanceof Number &&
          span["parent_id"] instanceof Number &&
          span["start"] instanceof Number &&
          span["duration"] instanceof Number &&
          span["error"] == 0 &&
          span["metrics"] instanceof Map &&
          span["meta"] instanceof Map
      }
    })
  }

  def "test printing regular hex ids"() {

    given:
    def printStream = Mock(PrintStream)
    def writer = new PrintingWriter(printStream, true)

    when:
    writer.write(sampleTrace)

    then:
    1 * printStream.println({
      List<Map> result = adapter.fromJson(it as String)
      result.size() == sampleTrace.size() && result.every { span ->
        return span["service"] == "fakeService" &&
          span["name"] == "fakeOperation" &&
          span["resource"] == "fakeResource" &&
          span["type"] == "fakeType" &&
          span["trace_id"] instanceof String &&
          span["span_id"] instanceof String &&
          span["parent_id"] instanceof String &&
          span["start"] instanceof Number &&
          span["duration"] instanceof Number &&
          span["error"] == 0 &&
          span["metrics"] instanceof Map &&
          span["meta"] instanceof Map
      }
    })
  }
}
