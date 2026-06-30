package com.datadog.appsec.sca;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScaCveDatabaseTest {

  private static final String MINIMAL_JSON =
      "{\"version\":1,\"entries\":["
          + "{\"vuln_id\":\"GHSA-test-1234-5678\","
          + "\"artifact\":\"com.example:lib\","
          + "\"version_ranges\":[\"< 2.0.0\"],"
          + "\"symbols\":["
          + "{\"class\":\"com/example/Foo\",\"method\":\"dangerousOp\"},"
          + "{\"class\":\"com/example/Bar\",\"method\":\"dangerousOp\"}"
          + "]}]}";

  @Test
  void loadsFromJson() throws Exception {
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(MINIMAL_JSON));

    assertFalse(db.isEmpty());
    assertEquals(2, db.size()); // 2 unique class names
  }

  @Test
  void indexedByClassName() throws Exception {
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(MINIMAL_JSON));

    List<ScaEntry> entries = db.entriesForClass("com/example/Foo");
    assertNotNull(entries);
    assertEquals(1, entries.size());
    assertEquals("GHSA-test-1234-5678", entries.get(0).vulnId());
    assertEquals("com.example:lib", entries.get(0).artifact());
  }

  @Test
  void unknownClassReturnsNull() throws Exception {
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(MINIMAL_JSON));

    assertNull(db.entriesForClass("com/example/Unknown"));
  }

  @Test
  void emptyEntriesProducesEmptyDatabase() throws Exception {
    String json = "{\"version\":1,\"entries\":[]}";
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    assertTrue(db.isEmpty());
  }

  @Test
  void malformedEntryIsSkipped() throws Exception {
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":null,\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 2.0.0\"],\"symbols\":[{\"class\":\"com/example/Foo\",\"method\":\"op\"}]},"
            + "{\"vuln_id\":\"GHSA-good-0000-0000\",\"artifact\":\"com.example:other\","
            + "\"version_ranges\":[\"< 1.0.0\"],\"symbols\":[{\"class\":\"com/example/Good\",\"method\":\"op\"}]}"
            + "]}";

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    assertNull(db.entriesForClass("com/example/Foo"));
    assertNotNull(db.entriesForClass("com/example/Good"));
  }

  @Test
  void symbolWithNullMethodIsSkipped() throws Exception {
    // A symbol with method=null is treated as malformed and skipped.
    // If all symbols in an entry are null-method, the whole entry is dropped.
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":\"GHSA-null-method\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 1.0.0\"],"
            + "\"symbols\":[{\"class\":\"com/example/Foo\",\"method\":null}]}"
            + "]}";
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    assertTrue(db.isEmpty(), "Entry with all null-method symbols must be dropped");
  }

  @Test
  void symbolWithNullMethodSkippedButValidSymbolsKept() throws Exception {
    // When an entry has a mix of null-method and valid symbols, only the valid ones are kept.
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":\"GHSA-mixed-method\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 1.0.0\"],"
            + "\"symbols\":["
            + "{\"class\":\"com/example/Foo\",\"method\":null},"
            + "{\"class\":\"com/example/Foo\",\"method\":\"readValue\"}"
            + "]}"
            + "]}";
    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    List<ScaEntry> entries = db.entriesForClass("com/example/Foo");
    assertNotNull(entries);
    List<ScaSymbol> symbols = entries.get(0).symbols();
    assertEquals(1, symbols.size(), "null-method symbol must be dropped; valid symbol kept");
    assertEquals("readValue", symbols.get(0).method());
  }

  @Test
  void multipleEntriesForSameClass() throws Exception {
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":\"GHSA-aaaa-0001-0001\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 2.0.0\"],\"symbols\":[{\"class\":\"com/example/Shared\",\"method\":\"op\"}]},"
            + "{\"vuln_id\":\"GHSA-bbbb-0002-0002\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 3.0.0\"],\"symbols\":[{\"class\":\"com/example/Shared\",\"method\":\"op\"}]}"
            + "]}";

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    List<ScaEntry> entries = db.entriesForClass("com/example/Shared");
    assertNotNull(entries);
    assertEquals(2, entries.size());
  }

  @Test
  void scaEntryMatchesVersions() {
    List<String> expectedRanges = singletonList("< 2.0.0");
    List<ScaSymbol> symbols = singletonList(new ScaSymbol("com/example/Foo", "op"));
    ScaEntry entry = new ScaEntry("GHSA-entry", "com.example:lib", expectedRanges, symbols);

    assertEquals(expectedRanges, entry.versionRanges());
    assertTrue(entry.isVersionVulnerable("1.9.9"));
    assertFalse(entry.isVersionVulnerable("2.0.0"));
  }

  @Test
  void scaEntryExposesImmutableLists() {
    List<String> ranges = singletonList("< 2.0.0");
    List<ScaSymbol> symbols = singletonList(new ScaSymbol("com/example/Foo", "op"));
    ScaEntry entry = new ScaEntry("GHSA-entry", "com.example:lib", ranges, symbols);

    assertThrows(UnsupportedOperationException.class, () -> entry.versionRanges().add("< 3.0.0"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> entry.symbols().add(new ScaSymbol("com/example/Bar", "op")));
  }

  @Test
  void entryWithMultipleSymbolsInSameClassIndexedOnce() throws Exception {
    // An entry with two symbols for the same class (e.g. Yaml.load + Yaml.loadAll) must appear
    // only once in the index. Duplicate entries cause the same bytecode callback to be injected
    // twice into each method, producing redundant bootstrap calls on every invocation.
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":\"GHSA-mjmj-j48q-9wg2\",\"artifact\":\"org.yaml:snakeyaml\","
            + "\"version_ranges\":[\"<= 1.33\"],\"symbols\":["
            + "{\"class\":\"org/yaml/snakeyaml/Yaml\",\"method\":\"load\"},"
            + "{\"class\":\"org/yaml/snakeyaml/Yaml\",\"method\":\"loadAll\"}"
            + "]}]}";

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    List<ScaEntry> entries = db.entriesForClass("org/yaml/snakeyaml/Yaml");
    assertNotNull(entries);
    assertEquals(1, entries.size(), "same entry must not appear twice even with multiple symbols");
    assertEquals(2, entries.get(0).symbols().size(), "entry must retain all method symbols");
  }

  @Test
  void loadFromClasspathSucceeds() {
    // Verifies the real sca_cves.json generated by generateScaCvesJson is valid and loadable
    ScaCveDatabase db = ScaCveDatabase.load();

    assertFalse(db.isEmpty(), "sca_cves.json should be on the classpath and contain entries");
    assertTrue(db.size() > 0);
  }

  @Test
  void junrarLocalFolderExtractorIsIndexed() {
    // Spot-check a known entry from the real database (com.github.junrar:junrar, GHSA-hf5p)
    ScaCveDatabase db = ScaCveDatabase.load();

    List<ScaEntry> entries = db.entriesForClass("com/github/junrar/LocalFolderExtractor");
    assertNotNull(entries, "junrar LocalFolderExtractor should be in the database");
    assertFalse(entries.isEmpty());
    assertTrue(
        entries.get(0).symbols().stream().allMatch(s -> s.method() != null),
        "all symbols must be method-level");
  }
}
