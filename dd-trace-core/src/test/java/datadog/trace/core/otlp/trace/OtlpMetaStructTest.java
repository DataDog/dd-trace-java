package datadog.trace.core.otlp.trace;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
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

        DDSpan span = (DDSpan) tracer.startSpan("test", "with-reports");
        span.setResourceName("with-reports");
        span.setSamplingPriority(PrioritySampling.USER_KEEP);
        span.setTag("_dd.appsec.json", "{\"triggers\":[]}");
        span.setTag("_dd.stack", "ordinary tag");
        span.setBaggageItem("ai_guard", "baggage value");
        for (String key : asList("_dd.stack", "ai_guard", "custom.report")) {
          span.setMetaStruct(key, report);
        }
        span.finish();

        DDSpan next = (DDSpan) tracer.startSpan("test", "without-reports");
        next.setResourceName("without-reports");
        next.setSamplingPriority(PrioritySampling.USER_KEEP);
        next.finish();

        OtlpTraceCollector collector =
            "json".equals(protocol) ? new OtlpTraceJsonCollector() : new OtlpTraceProtoCollector();
        collector.addTrace(singletonList(span));
        collector.addTrace(singletonList(next));
        OtlpPayload payload = collector.collectTraces();
        byte[] bytes = new byte[payload.getContentLength()];
        payload.getContent().get(bytes);
        Map<String, Map<String, byte[]>> reports =
            "json".equals(protocol) ? jsonReports(bytes) : protobufReports(bytes);

        assertEquals(2, reports.size());
        Map<String, byte[]> exported = reports.get("with-reports");
        assertNotNull(exported);
        assertEquals(span.getMetaStruct().keySet(), exported.keySet());
        ObjectMapper messagePack = new ObjectMapper(new MessagePackFactory());
        for (byte[] value : exported.values()) {
          assertEquals(report, messagePack.readValue(value, Map.class));
        }
        assertNotNull(reports.get("without-reports"));
        assertEquals(0, reports.get("without-reports").size());
      } finally {
        tracer.close();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, byte[]>> jsonReports(byte[] bytes) throws IOException {
    Map<String, Object> root = JsonMapper.fromJsonToMap(new String(bytes, StandardCharsets.UTF_8));
    Map<String, Map<String, byte[]>> reports = new HashMap<>();
    for (Map<String, Object> resource : (List<Map<String, Object>>) root.get("resourceSpans")) {
      for (Map<String, Object> scope : (List<Map<String, Object>>) resource.get("scopeSpans")) {
        for (Map<String, Object> span : (List<Map<String, Object>>) scope.get("spans")) {
          Map<String, byte[]> attributes = new HashMap<>();
          Set<String> keys = new HashSet<>();
          boolean legacyReportSeen = false;
          for (Map<String, Object> attribute : (List<Map<String, Object>>) span.get("attributes")) {
            Map<String, Object> value = (Map<String, Object>) attribute.get("value");
            assertTrue(keys.add((String) attribute.get("key")), "duplicate attribute");
            if ("_dd.appsec.json".equals(attribute.get("key"))) {
              assertEquals(singletonMap("stringValue", "{\"triggers\":[]}"), value);
              legacyReportSeen = true;
            }
            if (value.containsKey("bytesValue")) {
              assertEquals(singleton("bytesValue"), value.keySet());
              assertNull(
                  attributes.put(
                      (String) attribute.get("key"),
                      Base64.getDecoder().decode((String) value.get("bytesValue"))));
            }
          }
          assertEquals("with-reports".equals(span.get("name")), legacyReportSeen);
          reports.put((String) span.get("name"), attributes);
        }
      }
    }
    return reports;
  }

  private static Map<String, Map<String, byte[]>> protobufReports(byte[] bytes) throws IOException {
    Map<String, Map<String, byte[]>> reports = new HashMap<>();
    for (byte[] resource : messages(bytes, 1)) {
      for (byte[] scope : messages(resource, 2)) {
        for (byte[] span : messages(scope, 2)) {
          CodedInputStream input = CodedInputStream.newInstance(span);
          String name = null;
          Map<String, byte[]> attributes = new HashMap<>();
          Set<String> keys = new HashSet<>();
          boolean legacyReportSeen = false;
          while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 5) {
              name = input.readString();
            } else if (WireFormat.getTagFieldNumber(tag) == 9) {
              byte[] attribute = input.readByteArray();
              String key = new String(messages(attribute, 1).get(0), StandardCharsets.UTF_8);
              assertTrue(keys.add(key), "duplicate attribute");
              byte[] value = messages(attribute, 2).get(0);
              if ("_dd.appsec.json".equals(key)) {
                assertEquals(
                    "{\"triggers\":[]}",
                    new String(messages(value, 1).get(0), StandardCharsets.UTF_8));
                legacyReportSeen = true;
              }
              List<byte[]> values = messages(value, 7);
              if (!values.isEmpty()) {
                assertEquals(1, values.size());
                assertNull(attributes.put(key, values.get(0)));
              }
            } else {
              input.skipField(tag);
            }
          }
          assertNotNull(name);
          assertEquals("with-reports".equals(name), legacyReportSeen);
          reports.put(name, attributes);
        }
      }
    }
    return reports;
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
