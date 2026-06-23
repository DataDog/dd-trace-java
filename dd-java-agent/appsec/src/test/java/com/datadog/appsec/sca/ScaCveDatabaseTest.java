package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScaCveDatabaseTest {

  private static final String MINIMAL_JSON =
      "{\"version\":1,\"entries\":["
          + "{\"vuln_id\":\"GHSA-test-1234-5678\","
          + "\"artifact\":\"com.example:lib\","
          + "\"version_ranges\":[\"< 2.0.0\"],"
          + "\"symbols\":["
          + "{\"class\":\"com/example/Foo\",\"method\":null},"
          + "{\"class\":\"com/example/Bar\",\"method\":null}"
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
            + "\"version_ranges\":[\"< 2.0.0\"],\"symbols\":[{\"class\":\"com/example/Foo\",\"method\":null}]},"
            + "{\"vuln_id\":\"GHSA-good-0000-0000\",\"artifact\":\"com.example:other\","
            + "\"version_ranges\":[\"< 1.0.0\"],\"symbols\":[{\"class\":\"com/example/Good\",\"method\":null}]}"
            + "]}";

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    assertNull(db.entriesForClass("com/example/Foo"));
    assertNotNull(db.entriesForClass("com/example/Good"));
  }

  @Test
  void multipleEntriesForSameClass() throws Exception {
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":\"GHSA-aaaa-0001-0001\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 2.0.0\"],\"symbols\":[{\"class\":\"com/example/Shared\",\"method\":null}]},"
            + "{\"vuln_id\":\"GHSA-bbbb-0002-0002\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 3.0.0\"],\"symbols\":[{\"class\":\"com/example/Shared\",\"method\":null}]}"
            + "]}";

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    List<ScaEntry> entries = db.entriesForClass("com/example/Shared");
    assertNotNull(entries);
    assertEquals(2, entries.size());
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
  void classLevelSymbolDroppedWhenMethodLevelSymbolExistsForSameClass() throws Exception {
    // When an entry has both a class-level symbol (method=null) and method-level symbols for the
    // same class, the class-level entry is redundant and must be dropped at parse time.
    // Keeping both would cause the first-hit-wins hitRef in the registry to record the class-level
    // callsite, silently discarding the more specific method callsite.
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":\"GHSA-mixed-0000-0001\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 2.0.0\"],\"symbols\":["
            + "{\"class\":\"com/example/Foo\",\"method\":null},"
            + "{\"class\":\"com/example/Foo\",\"method\":\"readValue\"},"
            + "{\"class\":\"com/example/Foo\",\"method\":\"readValues\"}"
            + "]}]}";

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    List<ScaEntry> entries = db.entriesForClass("com/example/Foo");
    assertNotNull(entries);
    assertEquals(1, entries.size());
    List<ScaSymbol> symbols = entries.get(0).symbols();
    assertEquals(
        2, symbols.size(), "class-level symbol must be dropped when method-level symbols exist");
    assertTrue(
        symbols.stream().allMatch(s -> !s.isClassLevel()),
        "no class-level symbols should remain when method-level symbols are present");
  }

  @Test
  void classLevelSymbolKeptWhenNoMethodLevelSymbolForSameClass() throws Exception {
    // When different classes in the same entry have class-level and method-level symbols
    // respectively, only the class-level symbol for the class that also has method-level symbols
    // should be dropped. Unrelated classes are unaffected.
    String json =
        "{\"version\":1,\"entries\":["
            + "{\"vuln_id\":\"GHSA-mixed-0000-0002\",\"artifact\":\"com.example:lib\","
            + "\"version_ranges\":[\"< 2.0.0\"],\"symbols\":["
            + "{\"class\":\"com/example/ClassA\",\"method\":null},"
            + "{\"class\":\"com/example/ClassB\",\"method\":null},"
            + "{\"class\":\"com/example/ClassB\",\"method\":\"dangerousOp\"}"
            + "]}]}";

    ScaCveDatabase db = ScaCveDatabase.parse(new StringReader(json));

    // ClassA has only a class-level symbol — must remain unchanged
    List<ScaEntry> entriesA = db.entriesForClass("com/example/ClassA");
    assertNotNull(entriesA, "ClassA (class-level only) must still be indexed");
    long classLevelA =
        entriesA.get(0).symbols().stream()
            .filter(s -> s.className().equals("com/example/ClassA") && s.isClassLevel())
            .count();
    assertEquals(1, classLevelA, "ClassA class-level symbol must not be dropped");

    // ClassB has both class-level and method-level: class-level must be dropped
    List<ScaEntry> entriesB = db.entriesForClass("com/example/ClassB");
    assertNotNull(entriesB, "ClassB must still be indexed under its method-level symbol");
    long classLevelB =
        entriesB.get(0).symbols().stream()
            .filter(s -> s.className().equals("com/example/ClassB") && s.isClassLevel())
            .count();
    assertEquals(0, classLevelB, "class-level symbol for ClassB must be dropped");
    long methodLevelB =
        entriesB.get(0).symbols().stream()
            .filter(s -> s.className().equals("com/example/ClassB") && !s.isClassLevel())
            .count();
    assertEquals(1, methodLevelB, "method-level symbol for ClassB must be retained");
  }

  @Test
  void loadFromClasspathSucceeds() {
    // Verifies the real sca_cves.json generated by generateScaCvesJson is valid and loadable
    ScaCveDatabase db = ScaCveDatabase.load();

    assertFalse(db.isEmpty(), "sca_cves.json should be on the classpath and contain entries");
    assertTrue(db.size() > 0);
  }

  @Test
  void jacksonDatabindObjectMapperIsIndexed() {
    // Spot-check a known entry from the real database
    ScaCveDatabase db = ScaCveDatabase.load();

    List<ScaEntry> entries = db.entriesForClass("com/fasterxml/jackson/databind/ObjectMapper");
    assertNotNull(entries, "jackson-databind ObjectMapper should be in the database");
    assertFalse(entries.isEmpty());
  }
}
