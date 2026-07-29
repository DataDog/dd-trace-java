package datadog.openfeature.internal.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UfcParserTest {

  private final UfcParser parser = new UfcParser();

  @Test
  void parsesRawUfc() throws Exception {
    final ConfigurationSnapshot snapshot = parser.parse(Fixtures.UFC.getBytes(UTF_8));

    assertEquals("test", snapshot.environmentName);
    assertEquals(1, snapshot.flags.size());
    assertNotNull(snapshot.flags.get("message"));
  }

  @Test
  void parsesJsonApiResponse() throws Exception {
    final String response =
        "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
            + Fixtures.UFC
            + "}}";

    assertEquals(1, parser.parse(response.getBytes(UTF_8)).flags.size());
  }

  @Test
  void parsesCompleteUfcModelAndFreezesNestedValues() throws Exception {
    final String content =
        "{"
            + "\"createdAt\":\"2026-01-01T00:00:00Z\","
            + "\"format\":\"SERVER\","
            + "\"environment\":{\"name\":\"production\"},"
            + "\"flags\":{\"targeted\":{"
            + "\"enabled\":true,"
            + "\"variationType\":\"JSON\","
            + "\"variations\":{\"on\":{\"value\":{\"nested\":[1,true]}}},"
            + "\"allocations\":[{"
            + "\"key\":\"allocation\","
            + "\"startAt\":\"2025-01-01T00:00:00Z\","
            + "\"endAt\":\"invalid-date\","
            + "\"doLog\":true,"
            + "\"rules\":[{\"conditions\":[{"
            + "\"operator\":\"ONE_OF\",\"attribute\":\"country\",\"value\":[\"US\"]"
            + "}]}],"
            + "\"splits\":[{"
            + "\"variationKey\":\"on\","
            + "\"serialId\":12,"
            + "\"extraLogging\":{\"reason\":\"test\"},"
            + "\"shards\":[{\"salt\":\"salt\",\"totalShards\":100,"
            + "\"ranges\":[{\"start\":0,\"end\":50}]}]"
            + "}]"
            + "}]"
            + "}}}";

    final ConfigurationSnapshot snapshot = parser.parse(content.getBytes(UTF_8));
    final ConfigurationSnapshot.Flag flag = snapshot.flags.get("targeted");
    final ConfigurationSnapshot.Allocation allocation = flag.allocations.get(0);
    final ConfigurationSnapshot.Split split = allocation.splits.get(0);

    assertEquals("targeted", flag.key);
    assertEquals(ConfigurationSnapshot.ValueType.JSON, flag.variationType);
    assertNotNull(allocation.startAtMillis);
    assertEquals(null, allocation.endAtMillis);
    assertEquals(
        ConfigurationSnapshot.ConditionOperator.ONE_OF,
        allocation.rules.get(0).conditions.get(0).operator);
    assertEquals(12, split.serialId);
    assertEquals("test", split.extraLogging.get("reason"));
    assertEquals(100, split.shards.get(0).totalShards);
    assertEquals(50, split.shards.get(0).ranges.get(0).end);
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((Map<String, Object>) flag.variations.get("on").value).put("new", true));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            ((List<Object>) ((Map<String, Object>) flag.variations.get("on").value).get("nested"))
                .add("new"));
  }

  @Test
  void skipsMalformedFlagAndKeepsValidFlag() throws Exception {
    final String content =
        Fixtures.UFC.replace("\"flags\":{", "\"flags\":{\"broken\":{\"enabled\":\"yes\"},");

    final ConfigurationSnapshot snapshot = parser.parse(content.getBytes(UTF_8));

    assertFalse(snapshot.flags.containsKey("broken"));
    assertNotNull(snapshot.flags.get("message"));
  }

  @Test
  void rejectsWrongJsonApiType() {
    assertThrows(
        IOException.class,
        () -> parser.parse("{\"data\":{\"type\":\"other\",\"attributes\":{}}}".getBytes(UTF_8)));
  }

  @Test
  void rejectsEmptyMalformedAndStructurallyInvalidDocuments() {
    assertThrows(IOException.class, () -> parser.parse(null));
    assertThrows(IOException.class, () -> parser.parse(new byte[0]));
    assertThrows(IOException.class, () -> parser.parse("{".getBytes(UTF_8)));
    assertThrows(IOException.class, () -> parser.parse("[]".getBytes(UTF_8)));
    assertThrows(IOException.class, () -> parser.parse("{}".getBytes(UTF_8)));
    assertThrows(
        IOException.class,
        () ->
            parser.parse("{\"data\":{\"type\":\"universal-flag-configuration\"}}".getBytes(UTF_8)));
  }

  @Test
  void skipsFlagsWithInvalidRequiredFields() throws Exception {
    final String content =
        "{\"flags\":{"
            + "\"enabled\":{\"enabled\":\"yes\",\"variationType\":\"STRING\",\"variations\":{}},"
            + "\"type\":{\"enabled\":true,\"variationType\":\"UNKNOWN\",\"variations\":{}},"
            + "\"variants\":{\"enabled\":true,\"variationType\":\"STRING\",\"variations\":[]},"
            + "\"allocations\":{\"enabled\":true,\"variationType\":\"STRING\","
            + "\"variations\":{},\"allocations\":\"bad\"},"
            + "\"operator\":{\"enabled\":true,\"variationType\":\"STRING\","
            + "\"variations\":{\"on\":{\"value\":\"on\"}},\"allocations\":[{"
            + "\"key\":\"a\",\"rules\":[{\"conditions\":[{"
            + "\"operator\":\"UNKNOWN\",\"attribute\":\"id\"}]}],\"splits\":[]}]}"
            + "}}";

    assertTrue(parser.parse(content.getBytes(UTF_8)).flags.isEmpty());
  }
}
