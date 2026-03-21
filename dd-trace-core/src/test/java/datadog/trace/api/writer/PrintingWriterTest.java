package datadog.trace.api.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.PrintingWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrintingWriterTest extends DDCoreSpecification {

  CoreTracer tracer;
  List<DDSpan> sampleTrace;
  List<DDSpan> secondTrace;

  JsonAdapter<Map<String, List<List<Map>>>> adapter =
      new Moshi.Builder()
          .build()
          .adapter(
              Types.newParameterizedType(
                  Map.class,
                  String.class,
                  Types.newParameterizedType(
                      List.class, Types.newParameterizedType(List.class, Map.class))));

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentTracer.SpanBuilder builder =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType");

    sampleTrace = Arrays.asList((DDSpan) builder.start(), (DDSpan) builder.start());
    secondTrace = Arrays.asList((DDSpan) builder.start());
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void testPrintingRegularIds() throws IOException {
    Buffer buffer = new Buffer();
    PrintingWriter writer = new PrintingWriter(buffer.outputStream(), false);

    writer.write(sampleTrace);
    Map<String, List<List<Map>>> result =
        adapter.fromJson(buffer.readString(StandardCharsets.UTF_8));

    assertEquals(sampleTrace.size(), result.get("traces").get(0).size());
    for (Map span : result.get("traces").get(0)) {
      assertEquals("fakeService", span.get("service"));
      assertEquals("fakeOperation", span.get("name"));
      assertEquals("fakeResource", span.get("resource"));
      assertEquals("fakeType", span.get("type"));
      assertInstanceOf(Number.class, span.get("trace_id"));
      assertInstanceOf(Number.class, span.get("span_id"));
      assertInstanceOf(Number.class, span.get("parent_id"));
      assertInstanceOf(Number.class, span.get("start"));
      assertInstanceOf(Number.class, span.get("duration"));
      assertEquals(0.0, ((Number) span.get("error")).doubleValue(), 0.0);
      assertInstanceOf(Map.class, span.get("metrics"));
      assertInstanceOf(Map.class, span.get("meta"));
    }

    writer.write(secondTrace);
    result = adapter.fromJson(buffer.readString(StandardCharsets.UTF_8));

    assertEquals(secondTrace.size(), result.get("traces").get(0).size());
    for (Map span : result.get("traces").get(0)) {
      assertEquals("fakeService", span.get("service"));
      assertEquals("fakeOperation", span.get("name"));
      assertEquals("fakeResource", span.get("resource"));
      assertEquals("fakeType", span.get("type"));
      assertInstanceOf(Number.class, span.get("trace_id"));
      assertInstanceOf(Number.class, span.get("span_id"));
      assertInstanceOf(Number.class, span.get("parent_id"));
      assertInstanceOf(Number.class, span.get("start"));
      assertInstanceOf(Number.class, span.get("duration"));
      assertEquals(0.0, ((Number) span.get("error")).doubleValue(), 0.0);
      assertInstanceOf(Map.class, span.get("metrics"));
      assertInstanceOf(Map.class, span.get("meta"));
    }
  }

  @Test
  void testPrintingRegularHexIds() throws IOException {
    Buffer buffer = new Buffer();
    PrintingWriter writer = new PrintingWriter(buffer.outputStream(), true);

    writer.write(sampleTrace);
    Map<String, List<List<Map>>> result =
        adapter.fromJson(buffer.readString(StandardCharsets.UTF_8));

    assertEquals(sampleTrace.size(), result.get("traces").get(0).size());
    for (Map span : result.get("traces").get(0)) {
      assertEquals("fakeService", span.get("service"));
      assertEquals("fakeOperation", span.get("name"));
      assertEquals("fakeResource", span.get("resource"));
      assertEquals("fakeType", span.get("type"));
      assertInstanceOf(String.class, span.get("trace_id"));
      assertInstanceOf(String.class, span.get("span_id"));
      assertInstanceOf(String.class, span.get("parent_id"));
      assertInstanceOf(Number.class, span.get("start"));
      assertInstanceOf(Number.class, span.get("duration"));
      assertEquals(0.0, ((Number) span.get("error")).doubleValue(), 0.0);
      assertInstanceOf(Map.class, span.get("metrics"));
      assertInstanceOf(Map.class, span.get("meta"));
    }
  }

  @Test
  void testPrintingMultipleTraces() throws IOException {
    Buffer buffer = new Buffer();
    PrintingWriter writer = new PrintingWriter(buffer.outputStream(), false);

    writer.write(sampleTrace);
    writer.write(secondTrace);
    Map<String, List<List<Map>>> result1 = adapter.fromJson(buffer.readUtf8Line());
    Map<String, List<List<Map>>> result2 = adapter.fromJson(buffer.readUtf8Line());

    assertEquals(sampleTrace.size(), result1.get("traces").get(0).size());
    for (Map span : result2.get("traces").get(0)) {
      assertEquals("fakeService", span.get("service"));
      assertEquals("fakeOperation", span.get("name"));
      assertEquals("fakeResource", span.get("resource"));
      assertEquals("fakeType", span.get("type"));
      assertInstanceOf(Number.class, span.get("trace_id"));
      assertInstanceOf(Number.class, span.get("span_id"));
      assertInstanceOf(Number.class, span.get("parent_id"));
      assertInstanceOf(Number.class, span.get("start"));
      assertInstanceOf(Number.class, span.get("duration"));
      assertEquals(0.0, ((Number) span.get("error")).doubleValue(), 0.0);
      assertInstanceOf(Map.class, span.get("metrics"));
      assertInstanceOf(Map.class, span.get("meta"));
    }
    assertEquals(secondTrace.size(), result2.get("traces").get(0).size());
    for (Map span : result2.get("traces").get(0)) {
      assertEquals("fakeService", span.get("service"));
      assertEquals("fakeOperation", span.get("name"));
      assertEquals("fakeResource", span.get("resource"));
      assertEquals("fakeType", span.get("type"));
      assertInstanceOf(Number.class, span.get("trace_id"));
      assertInstanceOf(Number.class, span.get("span_id"));
      assertInstanceOf(Number.class, span.get("parent_id"));
      assertInstanceOf(Number.class, span.get("start"));
      assertInstanceOf(Number.class, span.get("duration"));
      assertEquals(0.0, ((Number) span.get("error")).doubleValue(), 0.0);
      assertInstanceOf(Map.class, span.get("metrics"));
      assertInstanceOf(Map.class, span.get("meta"));
    }
  }
}
