package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link TagMap.EntryReader#openTelemetryTag()} — the reader's view of its tag in the OpenTelemetry
 * namespace. {@link KnownTagCodec#openTelemetryTagOf} owns the naming and returns null for a tag it
 * does not know; the reader completes that by falling back to its own key, which is the one thing
 * the codec cannot do.
 */
class TagMapNamespaceNamesTest {

  @Test
  void renamedTagReadsUnderItsOpenTelemetryName() {
    assertEquals("http.request.method", otelTagOf("http.method", "GET"));
  }

  @Test
  void tagWithoutARenamePassesThroughUnderItsDatadogName() {
    // http.route declares no otel-name, so the OpenTelemetry namespace keeps the Datadog spelling.
    assertEquals("http.route", otelTagOf("http.route", "/orders/:id"));
  }

  @Test
  void customTagFallsBackToItsOwnKey() {
    // Not in the registry at all: the codec has no name for it, so the reader supplies its key.
    assertEquals("my.app.tenant", otelTagOf("my.app.tenant", "acme"));
  }

  @Test
  void anOpenTelemetrySpellingNormalizesToTheOneName() {
    // keyOf is many->one, so an entry written under the OTel name resolves to the same tag and
    // reads back under that name -- not under two different ones depending on how it was written.
    assertEquals("http.request.method", otelTagOf("http.request.method", "POST"));
    assertEquals(
        otelTagOf("http.method", "GET"),
        otelTagOf("http.request.method", "POST"),
        "both spellings of one tag must read under the same OpenTelemetry name");
  }

  @Test
  void openTelemetryNameDiffersFromTheDatadogNameForARenamedTag() {
    TagMap map = TagMap.create();
    map.set("http.method", "GET");
    TagMap.EntryReader reader = readerFor(map, "http.method");
    assertEquals("http.method", reader.tag(), "tag() stays the key as written");
    assertNotEquals(
        reader.tag(), reader.openTelemetryTag(), "a rename must actually change the emitted name");
  }

  private static String otelTagOf(String tag, Object value) {
    TagMap map = TagMap.create();
    map.set(tag, value);
    return readerFor(map, tag).openTelemetryTag();
  }

  /**
   * The entry for {@code tag}, having first checked that iteration agrees with it — the iterator
   * may hand out a reused flyweight rather than the entry itself, so the two paths are worth
   * pinning together.
   */
  private static TagMap.EntryReader readerFor(TagMap map, String tag) {
    Map<String, String> otelByTag = new HashMap<>();
    map.forEach(reader -> otelByTag.put(reader.tag(), reader.openTelemetryTag()));

    TagMap.Entry entry = map.getEntry(tag);
    assertEquals(
        otelByTag.get(tag),
        entry.openTelemetryTag(),
        "iteration and getEntry must agree on the OpenTelemetry name for " + tag);
    return entry;
  }
}
