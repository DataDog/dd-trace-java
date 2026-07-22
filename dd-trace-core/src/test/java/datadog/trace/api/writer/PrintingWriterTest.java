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
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"unchecked", "rawtypes"})
public class PrintingWriterTest extends DDCoreJavaSpecification {

  private CoreTracer tracer;
  private List<DDSpan> sampleTrace;
  private List<DDSpan> secondTrace;
  private JsonAdapter adapter;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
    adapter =
        new Moshi.Builder()
            .build()
            .adapter(
                Types.newParameterizedType(
                    Map.class,
                    String.class,
                    Types.newParameterizedType(
                        List.class, Types.newParameterizedType(List.class, Map.class))));

    AgentTracer.SpanBuilder builder =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType");

    sampleTrace = Arrays.asList((DDSpan) builder.start(), (DDSpan) builder.start());
    secondTrace = Collections.singletonList((DDSpan) builder.start());
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void testPrintingRegularIds() throws Exception {
    Buffer buffer = new Buffer();
    PrintingWriter writer = new PrintingWriter(buffer.outputStream(), false);

    writer.write(sampleTrace);
    Map<String, List<List<Map>>> result =
        (Map<String, List<List<Map>>>) adapter.fromJson(buffer.readString(StandardCharsets.UTF_8));

    assertEquals(sampleTrace.size(), result.get("traces").get(0).size());
    for (Map span : result.get("traces").get(0)) {
      assertRegularSpanFields(span, false);
    }

    writer.write(secondTrace);
    result =
        (Map<String, List<List<Map>>>) adapter.fromJson(buffer.readString(StandardCharsets.UTF_8));

    assertEquals(secondTrace.size(), result.get("traces").get(0).size());
    for (Map span : result.get("traces").get(0)) {
      assertRegularSpanFields(span, false);
    }
  }

  @Test
  void testPrintingRegularHexIds() throws Exception {
    Buffer buffer = new Buffer();
    PrintingWriter writer = new PrintingWriter(buffer.outputStream(), true);

    writer.write(sampleTrace);
    Map<String, List<List<Map>>> result =
        (Map<String, List<List<Map>>>) adapter.fromJson(buffer.readString(StandardCharsets.UTF_8));

    assertEquals(sampleTrace.size(), result.get("traces").get(0).size());
    for (Map span : result.get("traces").get(0)) {
      assertRegularSpanFields(span, true);
    }
  }

  @Test
  void testPrintingMultipleTraces() throws Exception {
    Buffer buffer = new Buffer();
    PrintingWriter writer = new PrintingWriter(buffer.outputStream(), false);

    writer.write(sampleTrace);
    writer.write(secondTrace);
    Map<String, List<List<Map>>> result1 =
        (Map<String, List<List<Map>>>) adapter.fromJson(buffer.readUtf8Line());
    Map<String, List<List<Map>>> result2 =
        (Map<String, List<List<Map>>>) adapter.fromJson(buffer.readUtf8Line());

    assertEquals(sampleTrace.size(), result1.get("traces").get(0).size());
    for (Map span : result2.get("traces").get(0)) {
      assertRegularSpanFields(span, false);
    }
    assertEquals(secondTrace.size(), result2.get("traces").get(0).size());
    for (Map span : result2.get("traces").get(0)) {
      assertRegularSpanFields(span, false);
    }
  }

  private void assertRegularSpanFields(Map span, boolean hexIds) {
    assertEquals("fakeService", span.get("service"));
    assertEquals("fakeOperation", span.get("name"));
    assertEquals("fakeResource", span.get("resource"));
    assertEquals("fakeType", span.get("type"));
    if (hexIds) {
      assertInstanceOf(String.class, span.get("trace_id"));
      assertInstanceOf(String.class, span.get("span_id"));
      assertInstanceOf(String.class, span.get("parent_id"));
    } else {
      assertInstanceOf(Number.class, span.get("trace_id"));
      assertInstanceOf(Number.class, span.get("span_id"));
      assertInstanceOf(Number.class, span.get("parent_id"));
    }
    assertInstanceOf(Number.class, span.get("start"));
    assertInstanceOf(Number.class, span.get("duration"));
    assertEquals(0, ((Number) span.get("error")).intValue());
    assertInstanceOf(Map.class, span.get("metrics"));
    assertInstanceOf(Map.class, span.get("meta"));
  }
}
