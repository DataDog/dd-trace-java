package datadog.trace.api.writer

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.PrintingWriter
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import okio.Buffer

import java.nio.charset.StandardCharsets

class PrintingWriterTest extends DDSpecification {

  def tracer = CoreTracer.builder().writer(new ListWriter()).build()
  def sampleTrace
  def secondTrace

  def adapter = new Moshi.Builder().build().adapter(Types.newParameterizedType(Map, String, Types.newParameterizedType(List, Map)))

  def setup() {
    def builder = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .withSpanType("fakeType")

    sampleTrace = [builder.start(), builder.start()]
    secondTrace = [builder.start()]
  }

  def cleanup() {
    tracer?.close()
  }

  def "test printing regular ids"() {
    given:
    def buffer = new Buffer()
    def writer = new PrintingWriter(buffer.outputStream(), false)

    when:
    writer.write(sampleTrace)
    Map<String, List<Map>> result = adapter.fromJson(buffer.readString(StandardCharsets.UTF_8))

    then:
    result["traces"].size() == sampleTrace.size()
    result["traces"].each {
      assert it["service"] == "fakeService"
      assert it["name"] == "fakeOperation"
      assert it["resource"] == "fakeResource"
      assert it["type"] == "fakeType"
      assert it["trace_id"] instanceof Number
      assert it["span_id"] instanceof Number
      assert it["parent_id"] instanceof Number
      assert it["start"] instanceof Number
      assert it["duration"] instanceof Number
      assert it["error"] == 0
      assert it["metrics"] instanceof Map
      assert it["meta"] instanceof Map
    }

    when:
    writer.write(secondTrace)
    result = adapter.fromJson(buffer.readString(StandardCharsets.UTF_8))

    then:
    result["traces"].size() == secondTrace.size()
    result["traces"].each {
      assert it["service"] == "fakeService"
      assert it["name"] == "fakeOperation"
      assert it["resource"] == "fakeResource"
      assert it["type"] == "fakeType"
      assert it["trace_id"] instanceof Number
      assert it["span_id"] instanceof Number
      assert it["parent_id"] instanceof Number
      assert it["start"] instanceof Number
      assert it["duration"] instanceof Number
      assert it["error"] == 0
      assert it["metrics"] instanceof Map
      assert it["meta"] instanceof Map
    }
  }

  def "test printing regular hex ids"() {

    given:
    def buffer = new Buffer()
    def writer = new PrintingWriter(buffer.outputStream(), true)

    when:
    writer.write(sampleTrace)
    Map<String, List<Map>> result = adapter.fromJson(buffer.readString(StandardCharsets.UTF_8))

    then:
    result["traces"].size() == sampleTrace.size()
    result["traces"].each {
      assert it["service"] == "fakeService"
      assert it["name"] == "fakeOperation"
      assert it["resource"] == "fakeResource"
      assert it["type"] == "fakeType"
      assert it["trace_id"] instanceof String
      assert it["span_id"] instanceof String
      assert it["parent_id"] instanceof String
      assert it["start"] instanceof Number
      assert it["duration"] instanceof Number
      assert it["error"] == 0
      assert it["metrics"] instanceof Map
      assert it["meta"] instanceof Map
    }
  }
}
