package datadog.openfeature.internal.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UfcParserTest {

  private final UfcParser parser = new UfcParser();

  @Test
  void parsesRawUfc() throws Exception {
    final ServerConfiguration snapshot = parser.parse(Fixtures.UFC.getBytes(UTF_8));

    assertEquals("test", snapshot.environment.name);
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
    assertEquals(1, parser.parseJsonApi(response.getBytes(UTF_8)).flags.size());
    assertThrows(IOException.class, () -> parser.parseJsonApi(Fixtures.UFC.getBytes(UTF_8)));
  }

  @Test
  void parsesCompleteUfcModel() throws Exception {
    final String content =
        "{"
            + "\"createdAt\":\"2026-01-01T00:00:00Z\","
            + "\"format\":\"SERVER\","
            + "\"environment\":{\"name\":\"production\"},"
            + "\"flags\":{\"targeted\":{"
            + "\"key\":\"targeted\","
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

    final ServerConfiguration snapshot = parser.parse(content.getBytes(UTF_8));
    final Flag flag = snapshot.flags.get("targeted");
    final Allocation allocation = flag.allocations.get(0);
    final Split split = allocation.splits.get(0);

    assertEquals("targeted", flag.key);
    assertEquals(ValueType.JSON, flag.variationType);
    assertNotNull(allocation.startAt);
    assertEquals(null, allocation.endAt);
    assertEquals(ConditionOperator.ONE_OF, allocation.rules.get(0).conditions.get(0).operator);
    assertEquals(12, split.serialId);
    assertEquals("test", split.extraLogging.get("reason"));
    assertEquals(100, split.shards.get(0).totalShards);
    assertEquals(50, split.shards.get(0).ranges.get(0).end);
  }

  @Test
  void ignoresUnknownAdditiveFieldsAtEveryUfcLevel() throws Exception {
    final String content =
        "{"
            + "\"futureEnvelope\":{\"version\":2},"
            + "\"data\":{"
            + "\"type\":\"universal-flag-configuration\","
            + "\"futureResource\":true,"
            + "\"attributes\":{"
            + "\"futureConfiguration\":{\"key\":\"value\"},"
            + "\"environment\":{\"name\":\"test\",\"futureEnvironment\":true},"
            + "\"flags\":{\"message\":{"
            + "\"key\":\"message\","
            + "\"enabled\":true,"
            + "\"variationType\":\"STRING\","
            + "\"futureFlag\":1,"
            + "\"variations\":{\"on\":{"
            + "\"key\":\"on\",\"value\":\"hello\",\"futureVariant\":\"ignored\""
            + "}},"
            + "\"allocations\":[{"
            + "\"key\":\"allocation\","
            + "\"futureAllocation\":{},"
            + "\"rules\":[{"
            + "\"futureRule\":true,"
            + "\"conditions\":[{"
            + "\"operator\":\"IS_NULL\","
            + "\"attribute\":\"missing\","
            + "\"value\":true,"
            + "\"futureCondition\":[]"
            + "}]"
            + "}],"
            + "\"splits\":[{"
            + "\"variationKey\":\"on\","
            + "\"serialId\":7,"
            + "\"futureSplit\":false,"
            + "\"shards\":[{"
            + "\"salt\":\"salt\","
            + "\"totalShards\":1,"
            + "\"futureShard\":null,"
            + "\"ranges\":[{\"start\":0,\"end\":1,\"futureRange\":9}]"
            + "}]"
            + "}]"
            + "}]"
            + "}}"
            + "}"
            + "}"
            + "}";

    final ServerConfiguration snapshot = parser.parseJsonApi(content.getBytes(UTF_8));
    final Flag flag = snapshot.flags.get("message");
    final Allocation allocation = flag.allocations.get(0);
    final Split split = allocation.splits.get(0);

    assertEquals("test", snapshot.environment.name);
    assertEquals("hello", flag.variations.get("on").value);
    assertEquals(ConditionOperator.IS_NULL, allocation.rules.get(0).conditions.get(0).operator);
    assertEquals(7, split.serialId);
    assertEquals(1, split.shards.get(0).ranges.get(0).end);
  }

  @Test
  void parsesOffsetDatesAndMapsInvalidDatesToNull() throws Exception {
    final String content =
        "{"
            + "\"flags\":{\"flag\":{"
            + "\"enabled\":true,"
            + "\"variationType\":\"STRING\","
            + "\"variations\":{\"on\":{\"value\":\"on\"}},"
            + "\"allocations\":["
            + "{\"key\":\"positive\",\"startAt\":\"2023-01-01T01:00:00+01:00\","
            + "\"splits\":[]},"
            + "{\"key\":\"negative\",\"startAt\":\"2023-01-01T00:00:00-05:00\","
            + "\"splits\":[]},"
            + "{\"key\":\"invalid\",\"startAt\":\"not-a-date\",\"splits\":[]}"
            + "]"
            + "}}}";

    final List<Allocation> allocations =
        parser.parse(content.getBytes(UTF_8)).flags.get("flag").allocations;

    assertEquals(Instant.parse("2023-01-01T00:00:00Z"), allocations.get(0).startAt.toInstant());
    assertEquals(Instant.parse("2023-01-01T05:00:00Z"), allocations.get(1).startAt.toInstant());
    assertEquals(null, allocations.get(2).startAt);
  }

  @Test
  void skipsMalformedFlagAndKeepsValidFlag() throws Exception {
    final String content =
        Fixtures.UFC.replace("\"flags\":{", "\"flags\":{\"broken\":{\"enabled\":\"yes\"},");

    final ServerConfiguration snapshot = parser.parse(content.getBytes(UTF_8));

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
    assertThrows(
        IOException.class,
        () ->
            parser.parse("{\"data\":{\"type\":\"universal-flag-configuration\"}}".getBytes(UTF_8)));
  }

  @Test
  void allowsMissingAndNullFlagMaps() throws Exception {
    assertEquals(null, parser.parse("{}".getBytes(UTF_8)).flags);
    assertEquals(null, parser.parse("{\"flags\":null}".getBytes(UTF_8)).flags);
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
