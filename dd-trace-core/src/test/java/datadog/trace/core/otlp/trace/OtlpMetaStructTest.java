package datadog.trace.core.otlp.trace;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.json.JsonMapper;
import datadog.trace.api.Config;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mockito.AdditionalAnswers;
import org.mockito.MockedStatic;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.tabletest.junit.TableTest;

@WithConfig(key = TracerConfig.SPAN_TAGS, value = "custom.report:default")
class OtlpMetaStructTest {
  @TableTest({
    "Scenario               | protocol | semantics",
    "protobuf Datadog names | protobuf | false    ",
    "protobuf OTel names    | protobuf | true     ",
    "JSON Datadog names     | json     | false    ",
    "JSON OTel names        | json     | true     "
  })
  void structuredReportsSurviveOtlpExport(String protocol, boolean semantics) throws IOException {
    Config config = mock(Config.class, AdditionalAnswers.delegatesTo(Config.get()));
    when(config.isTraceOtelSemanticsEnabled()).thenReturn(semantics);
    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      configMock.when(Config::get).thenReturn(config);
      CoreTracer tracer = CoreTracer.builder().writer(new LoggingWriter()).build();
      try {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("nested", singletonMap("values", asList(42, true, "café")));
        report.put("large", new String(new char[2048]).replace('\0', 'x'));
        StackTraceFrame frame =
            new StackTraceFrame(0, new StackTraceElement("example.App", "hello", "App.java", 42));
        StackTraceEvent stack =
            new StackTraceEvent(singletonList(frame), "java", "stack-1", "exploit detected");
        Map<String, Object> expectedFrame = new LinkedHashMap<>();
        expectedFrame.put("id", 0);
        expectedFrame.put("text", "example.App.hello(App.java:42)");
        expectedFrame.put("file", "App.java");
        expectedFrame.put("line", 42);
        expectedFrame.put("class_name", "example.App");
        expectedFrame.put("function", "hello");
        Map<String, Object> expectedStack = new LinkedHashMap<>();
        expectedStack.put("id", "stack-1");
        expectedStack.put("language", "java");
        expectedStack.put("message", "exploit detected");
        expectedStack.put("frames", singletonList(expectedFrame));
        Map<String, Object> aiGuard =
            singletonMap(
                "messages", singletonList(singletonMap("content", "Synthetic hello world")));
        Map<String, Object> expectedReports = new LinkedHashMap<>();
        expectedReports.put("_dd.stack", singletonMap("exploit", singletonList(expectedStack)));
        expectedReports.put("ai_guard", aiGuard);
        expectedReports.put("custom.report", report);
        expectedReports.put("http.request.method", singletonMap("value", "structured method"));

        DDSpan span = (DDSpan) tracer.startSpan("test", "with-reports");
        span.setResourceName("with-reports");
        span.setSamplingPriority(PrioritySampling.USER_KEEP);
        span.setTag("_dd.appsec.json", "{\"triggers\":[]}");
        span.setTag("_dd.stack", "ordinary tag");
        span.setTag("http.method", "GET");
        span.setBaggageItem("ai_guard", "baggage value");
        span.setMetaStruct("_dd.stack", singletonMap("exploit", singletonList(stack)));
        span.setMetaStruct("ai_guard", aiGuard);
        span.setMetaStruct("custom.report", report);
        span.setMetaStruct("http.request.method", expectedReports.get("http.request.method"));
        span.finish();

        DDSpan next = (DDSpan) tracer.startSpan("test", "without-reports");
        next.setResourceName("without-reports");
        next.setSamplingPriority(PrioritySampling.USER_KEEP);
        next.setTag("_dd.stack", "plain stack tag");
        next.setBaggageItem("ai_guard", "plain AI baggage");
        next.finish();

        OtlpTraceCollector collector =
            "json".equals(protocol) ? new OtlpTraceJsonCollector() : new OtlpTraceProtoCollector();
        collector.addTrace(singletonList(span));
        collector.addTrace(singletonList(next));
        OtlpPayload payload = collector.collectTraces();
        byte[] bytes = new byte[payload.getContentLength()];
        payload.getContent().get(bytes);
        Map<String, Map<String, byte[]>> reports =
            "json".equals(protocol)
                ? jsonReports(bytes, semantics)
                : protobufReports(bytes, semantics);

        assertEquals(2, reports.size());
        Map<String, byte[]> exported = reports.get("with-reports");
        assertNotNull(exported);
        assertEquals(span.getMetaStruct().keySet(), exported.keySet());
        ObjectMapper messagePack = new ObjectMapper(new MessagePackFactory());
        for (Map.Entry<String, byte[]> value : exported.entrySet()) {
          assertEquals(
              expectedReports.get(value.getKey()),
              messagePack.readValue(value.getValue(), Map.class));
        }
        assertNotNull(reports.get("without-reports"));
        assertEquals(0, reports.get("without-reports").size());
      } finally {
        tracer.close();
      }
    }
  }

  @TableTest({
    "Scenario                    | protocol | failOnFlush",
    "protobuf later trace        | protobuf | false      ",
    "protobuf payload collection | protobuf | true       ",
    "JSON later trace            | json     | false      ",
    "JSON payload collection     | json     | true       "
  })
  void malformedReportDoesNotDiscardBufferedTraces(String protocol, boolean failOnFlush)
      throws IOException {
    CoreTracer tracer = CoreTracer.builder().writer(new LoggingWriter()).build();
    try {
      DDSpan first = (DDSpan) tracer.startSpan("test", "first");
      first.setSamplingPriority(PrioritySampling.USER_KEEP);
      first.finish();
      DDSpan malformed = (DDSpan) tracer.startSpan("test", "malformed");
      malformed.setSamplingPriority(PrioritySampling.USER_KEEP);
      malformed.setMetaStruct("bad.report", singletonMap(1, "invalid map key"));
      malformed.setTag("bad.report", "valid tag");
      malformed.setBaggageItem("bad.baggage", "valid baggage");
      malformed.setMetaStruct("bad.baggage", singletonMap(1, "invalid map key"));
      malformed.setMetaStruct("custom.report", singletonMap(1, "invalid map key"));
      malformed.setMetaStruct("ai_guard", singletonMap("messages", singletonList("valid report")));
      malformed.finish();
      DDSpan last = (DDSpan) tracer.startSpan("test", "last");
      last.setSamplingPriority(PrioritySampling.USER_KEEP);
      last.finish();
      OtlpTraceCollector collector =
          "json".equals(protocol) ? new OtlpTraceJsonCollector() : new OtlpTraceProtoCollector();
      collector.addTrace(singletonList(first));
      collector.addTrace(singletonList(malformed));
      if (!failOnFlush) {
        collector.addTrace(singletonList(last));
      }
      OtlpPayload payload = collector.collectTraces();
      byte[] bytes = new byte[payload.getContentLength()];
      payload.getContent().get(bytes);
      Map<String, Map<String, byte[]>> reports =
          "json".equals(protocol) ? jsonReports(bytes, false) : protobufReports(bytes, false);
      assertEquals(failOnFlush ? 2 : 3, reports.size());
      assertTrue(reports.containsKey("first"));
      assertEquals(singleton("ai_guard"), reports.get("malformed").keySet());
      if (!failOnFlush) {
        assertTrue(reports.containsKey("last"));
      }
    } finally {
      tracer.close();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, byte[]>> jsonReports(byte[] bytes, boolean semantics)
      throws IOException {
    Map<String, Object> root = JsonMapper.fromJsonToMap(new String(bytes, StandardCharsets.UTF_8));
    Map<String, Map<String, byte[]>> reports = new HashMap<>();
    for (Map<String, Object> resource : (List<Map<String, Object>>) root.get("resourceSpans")) {
      for (Map<String, Object> scope : (List<Map<String, Object>>) resource.get("scopeSpans")) {
        for (Map<String, Object> span : (List<Map<String, Object>>) scope.get("spans")) {
          Map<String, byte[]> attributes = new HashMap<>();
          Set<String> keys = new HashSet<>();
          Map<String, String> strings = new HashMap<>();
          for (Map<String, Object> attribute : (List<Map<String, Object>>) span.get("attributes")) {
            Map<String, Object> value = (Map<String, Object>) attribute.get("value");
            assertTrue(keys.add((String) attribute.get("key")), "duplicate attribute");
            if (value.containsKey("stringValue")) {
              strings.put((String) attribute.get("key"), (String) value.get("stringValue"));
            }
            if (value.containsKey("bytesValue")) {
              assertEquals(singleton("bytesValue"), value.keySet());
              assertNull(
                  attributes.put(
                      (String) attribute.get("key"),
                      Base64.getDecoder().decode((String) value.get("bytesValue"))));
            }
          }
          assertOrdinaryTags((String) span.get("name"), strings, semantics);
          reports.put((String) span.get("name"), attributes);
        }
      }
    }
    return reports;
  }

  private static Map<String, Map<String, byte[]>> protobufReports(byte[] bytes, boolean semantics)
      throws IOException {
    Map<String, Map<String, byte[]>> reports = new HashMap<>();
    for (byte[] resource : messages(bytes, 1)) {
      for (byte[] scope : messages(resource, 2)) {
        for (byte[] span : messages(scope, 2)) {
          CodedInputStream input = CodedInputStream.newInstance(span);
          String name = null;
          Map<String, byte[]> attributes = new HashMap<>();
          Set<String> keys = new HashSet<>();
          Map<String, String> strings = new HashMap<>();
          while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 5) {
              name = input.readString();
            } else if (WireFormat.getTagFieldNumber(tag) == 9) {
              byte[] attribute = input.readByteArray();
              String key = new String(messages(attribute, 1).get(0), StandardCharsets.UTF_8);
              assertTrue(keys.add(key), "duplicate attribute");
              byte[] value = messages(attribute, 2).get(0);
              List<byte[]> stringValues = messages(value, 1);
              if (!stringValues.isEmpty()) {
                assertEquals(1, stringValues.size());
                strings.put(key, new String(stringValues.get(0), StandardCharsets.UTF_8));
              }
              List<byte[]> values = messages(value, 7);
              if (!values.isEmpty()) {
                assertEquals(1, values.size());
                CodedInputStream anyValue = CodedInputStream.newInstance(value);
                assertEquals((7 << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED, anyValue.readTag());
                anyValue.readByteArray();
                assertTrue(anyValue.isAtEnd(), "bytesValue must be the only AnyValue field");
                assertNull(attributes.put(key, values.get(0)));
              }
            } else {
              input.skipField(tag);
            }
          }
          assertNotNull(name);
          assertOrdinaryTags(name, strings, semantics);
          reports.put(name, attributes);
        }
      }
    }
    return reports;
  }

  private static void assertOrdinaryTags(
      String spanName, Map<String, String> strings, boolean semantics) {
    if ("with-reports".equals(spanName)) {
      assertEquals("{\"triggers\":[]}", strings.get("_dd.appsec.json"));
      assertFalse(strings.containsKey("_dd.stack"));
      assertFalse(strings.containsKey("ai_guard"));
      assertFalse(strings.containsKey("custom.report"));
      assertFalse(strings.containsKey("http.request.method"));
      if (semantics) {
        assertFalse(strings.containsKey("http.method"));
      } else {
        assertEquals("GET", strings.get("http.method"));
      }
    } else if ("without-reports".equals(spanName)) {
      assertEquals("plain stack tag", strings.get("_dd.stack"));
      assertEquals("plain AI baggage", strings.get("ai_guard"));
      assertEquals("default", strings.get("custom.report"));
    } else if ("malformed".equals(spanName)) {
      assertEquals("valid tag", strings.get("bad.report"));
      assertEquals("valid baggage", strings.get("bad.baggage"));
      assertEquals("default", strings.get("custom.report"));
    }
  }

  private static List<byte[]> messages(byte[] message, int field) throws IOException {
    List<byte[]> result = new ArrayList<>();
    CodedInputStream input = CodedInputStream.newInstance(message);
    while (!input.isAtEnd()) {
      int tag = input.readTag();
      if (WireFormat.getTagFieldNumber(tag) == field) {
        result.add(input.readByteArray());
      } else {
        input.skipField(tag);
      }
    }
    return result;
  }
}
