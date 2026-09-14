package datadog.trace.core;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The HTTP status is carried as an int and rendered only on demand, so that a serializer encoding
 * it numerically never pays for a string it will not send.
 */
class MetadataTest {

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 404, 500, 599})
  void rendersTheStatusOnlyWhenAsked(int status) {
    Metadata metadata = metadataWithStatus(status);

    assertEquals(status, metadata.getHttpStatusCode());
    assertEquals(Integer.toString(status), metadata.getHttpStatusCodeString().toString());
  }

  @Test
  void reportsNoStatusAsUnsetRatherThanZeroString() {
    Metadata metadata = metadataWithStatus(UNSET_STATUS);

    assertEquals(UNSET_STATUS, metadata.getHttpStatusCode());
    assertNull(
        metadata.getHttpStatusCodeString(),
        "an absent status must not render as \"0\": the string protocols write the key only when"
            + " the span carries a status");
  }

  @Test
  void reusesTheRenderedStatusAcrossSpans() {
    // The point of routing through RadixTreeCache rather than rendering per span: two spans with
    // the same status share one UTF8BytesString instead of allocating one apiece.
    UTF8BytesString first = metadataWithStatus(404).getHttpStatusCodeString();
    UTF8BytesString second = metadataWithStatus(404).getHttpStatusCodeString();

    assertSame(first, second);
  }

  private static Metadata metadataWithStatus(int status) {
    return new Metadata(
        Thread.currentThread().getId(),
        UTF8BytesString.create("main"),
        TagMap.fromMap(emptyMap()),
        emptyMap(),
        0,
        false,
        false,
        status,
        null,
        0,
        null,
        emptyList());
  }
}
