package datadog.trace.instrumentation.aws.v1.sqs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.messaging.DatadogAttributeParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageExtractAdapterTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CONTEXT =
      "{\"x-datadog-trace-id\":\"123\",\"x-datadog-parent-id\":\"456\"}";

  private static String attribute(String type, String value) throws IOException {
    return "{\"Type\":"
        + MAPPER.writeValueAsString(type)
        + ",\"Value\":"
        + MAPPER.writeValueAsString(value)
        + "}";
  }

  private static List<String> extract(String body, boolean baseline) throws IOException {
    List<String> result = new ArrayList<>();
    AgentPropagation.KeyClassifier classifier =
        (key, value) -> {
          result.add(key + "=" + value);
          return true;
        };
    if (baseline) {
      JsonNode node = MAPPER.readTree(body).path("MessageAttributes").path("_datadog");
      String value = node.path("Value").asText();
      String type = node.path("Type").asText();
      if ("String".equals(type)) {
        DatadogAttributeParser.forEachProperty(classifier, value);
      } else if ("Binary".equals(type)) {
        DatadogAttributeParser.forEachProperty(
            classifier, ByteBuffer.wrap(Base64.getDecoder().decode(value)));
      }
    } else {
      MessageExtractAdapter.GETTER.forEachKeyInBody(body, classifier);
    }
    return result;
  }

  @Test
  void extractsStringAndBinaryWithEitherFieldOrder() throws IOException {
    for (String type : new String[] {"String", "Binary"}) {
      String value =
          "Binary".equals(type)
              ? Base64.getEncoder().encodeToString(CONTEXT.getBytes(UTF_8))
              : CONTEXT;
      String attr = attribute(type, value);
      String reversed =
          "{\"Value\":" + MAPPER.writeValueAsString(value) + ",\"Type\":\"" + type + "\"}";
      for (String a : new String[] {attr, reversed}) {
        String body =
            "{\"Message\":\"ignored\",\"MessageAttributes\":{\"other\":{\"nested\":[1,2]},\"_datadog\":"
                + a
                + "},\"tail\":[{},true]}";
        assertEquals(extract(body, true), extract(body, false));
        assertEquals(2, extract(body, false).size());
      }
    }
  }

  @Test
  void preservesLastDuplicateAtEveryLevel() throws IOException {
    String attr = attribute("String", CONTEXT);
    String attrs = "{\"_datadog\":" + attr + "}";
    String[] bodies = {
      "{\"MessageAttributes\":" + attrs + ",\"MessageAttributes\":{}}",
      "{\"MessageAttributes\":{},\"MessageAttributes\":" + attrs + "}",
      "{\"MessageAttributes\":" + attrs + ",\"MessageAttributes\":null}",
      "{\"MessageAttributes\":{\"_datadog\":" + attr + ",\"_datadog\":null}}",
      "{\"MessageAttributes\":{\"_datadog\":null,\"_datadog\":" + attr + "}}",
      "{\"MessageAttributes\":{\"_datadog\":{\"Type\":\"Binary\",\"Type\":\"String\",\"Value\":false,\"Value\":"
          + MAPPER.writeValueAsString(CONTEXT)
          + "}}}",
      "{\"MessageAttributes\":{" + "\"_datadog\":" + attr + ",\"other\":1}}"
    };
    for (String body : bodies) assertEquals(extract(body, true), extract(body, false), body);
  }

  @Test
  void ignoresUnrelatedFieldsAndPreservesNodeConversions() throws IOException {
    String attr = attribute("String", CONTEXT);
    for (String value : new String[] {"null", "true", "123", "1.25", "[]", "{}", "\"\""}) {
      String[] bodies = {
        "{\"MessageAttributes\":" + value + "}",
        "{\"MessageAttributes\":{\"_datadog\":" + value + "}}",
        "{\"MessageAttributes\":{\"_datadog\":{\"Type\":\"String\",\"Value\":" + value + "}}}",
        "{\"nested\":{\"MessageAttributes\":{\"_datadog\":" + attr + "}},\"other\":" + value + "}"
      };
      for (String body : bodies) assertEquals(extract(body, true), extract(body, false), body);
    }
    for (String body : new String[] {"{}", "[]", "null", "42", "\"plain text\""}) {
      assertEquals(extract(body, true), extract(body, false), body);
    }
  }

  @Test
  void validatesTheEnvelopeBeforePublishingContext() throws IOException {
    String prefix = "{\"MessageAttributes\":{\"_datadog\":" + attribute("String", CONTEXT) + "}";
    for (String suffix :
        new String[] {"", ",\"tail\":[1,]}", ",\"tail\":\"\\uZZZZ\"}", ",\"tail\":{\"x\":}}"}) {
      String body = prefix + suffix;
      List<String> observed = new ArrayList<>();
      assertThrows(IOException.class, () -> extract(body, true));
      assertThrows(
          IOException.class,
          () ->
              MessageExtractAdapter.GETTER.forEachKeyInBody(
                  body,
                  (key, value) -> {
                    observed.add(key);
                    return true;
                  }));
      assertTrue(observed.isEmpty());
    }
  }

  @Test
  void preservesIgnoringTrailingRootValues() throws IOException {
    String body =
        "{\"MessageAttributes\":{\"_datadog\":" + attribute("String", CONTEXT) + "}} trailing";
    assertEquals(extract(body, true), extract(body, false));
  }
}
