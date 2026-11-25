package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.civisibility.coverage.SourceMap;
import org.junit.jupiter.api.Test;

class SmapParserTest {

  /** Written by kotlinc 2.x for a class that inlines its own function and stdlib functions. */
  private static final String KOTLIN =
      "SMAP\n"
          + "KotlinCoverageFixture.kt\n"
          + "Kotlin\n"
          + "*S Kotlin\n"
          + "*F\n"
          + "+ 1 KotlinCoverageFixture.kt\n"
          + "datadog/trace/civisibility/coverage/probe/fixtures/KotlinCoverageFixture\n"
          + "+ 2 _Collections.kt\n"
          + "kotlin/collections/CollectionsKt___CollectionsKt\n"
          + "+ 3 fake.kt\n"
          + "kotlin/jvm/internal/FakeKt\n"
          + "*L\n"
          + "1#1,291:1\n"
          + "224#1,4:303\n"
          + "1547#2:292\n"
          + "1618#2,3:293\n"
          + "1849#2,2:308\n"
          + "1#3:307\n"
          + "*S KotlinDebug\n"
          + "*F\n"
          + "+ 1 KotlinCoverageFixture.kt\n"
          + "datadog/trace/civisibility/coverage/probe/fixtures/KotlinCoverageFixture\n"
          + "*L\n"
          + "64#1:303,4\n"
          + "39#1:292\n"
          + "*E\n";

  @Test
  void mapsInlinedKotlinLinesToTheFilesTheyCameFrom() {
    SourceMap map = parse(KOTLIN, "datadog/trace/civisibility/coverage/probe/fixtures/");

    assertEquals(3, map.fileCount());
    assertEquals(
        "datadog/trace/civisibility/coverage/probe/fixtures/KotlinCoverageFixture.kt", map.file(0));
    assertEquals("kotlin/collections/_Collections.kt", map.file(1));
    assertEquals("kotlin/jvm/internal/fake.kt", map.file(2));

    assertMaps(map, 1, 0, 1);
    assertMaps(map, 291, 0, 291);
    assertMaps(map, 303, 0, 224); // the default stratum, not the call site KotlinDebug gives
    assertMaps(map, 306, 0, 227);
    assertMaps(map, 292, 1, 1547);
    assertMaps(map, 293, 1, 1618);
    assertMaps(map, 295, 1, 1620);
    assertMaps(map, 307, 2, 1);
    assertMaps(map, 309, 1, 1850);
    assertEquals(-1, map.map(0));
    assertEquals(-1, map.map(296));
    assertEquals(-1, map.map(310));
  }

  @Test
  void followsTheJsr45DefaultsForFileIdsRepeatCountsAndIncrements() {
    String smap =
        "SMAP\n"
            + "index_jsp.java\n"
            + "JSP\n"
            + "*S JSP\n"
            + "*F\n"
            + "+ 0 index.jsp\n"
            + "/WEB-INF/views/index.jsp\n"
            + "1 header.jsp\n"
            + "*L\n"
            + "1,3:10,2\n"
            + "5#1:20\n"
            + "6:21\n"
            + "*E\n";
    SourceMap map = parse(smap, "org/apache/jsp/");

    assertEquals("WEB-INF/views/index.jsp", map.file(0));
    assertEquals("org/apache/jsp/header.jsp", map.file(1));
    assertMaps(map, 10, 0, 1);
    assertMaps(map, 11, 0, 1);
    assertMaps(map, 12, 0, 2);
    assertMaps(map, 15, 0, 3);
    assertEquals(-1, map.map(16));
    assertMaps(map, 20, 1, 5);
    assertMaps(map, 21, 1, 6); // the file id carries over to a line info that omits it
  }

  @Test
  void readsTheStratumTheHeaderNames() {
    String smap =
        "SMAP\n"
            + "Foo.kt\n"
            + "Kotlin\n"
            + "*S KotlinDebug\n"
            + "*F\n"
            + "+ 1 Foo.kt\n"
            + "a/Foo\n"
            + "*L\n"
            + "1#1:100\n"
            + "*S Kotlin\n"
            + "*F\n"
            + "+ 1 Foo.kt\n"
            + "a/Foo\n"
            + "*L\n"
            + "7#1:100\n"
            + "*E\n";
    assertMaps(parse(smap, "a/"), 100, 0, 7);
  }

  @Test
  void skipsLinesThatDoNotParseAndEmbeddedMaps() {
    String smap =
        "SMAP\r\n"
            + "Foo.kt\r\n"
            + "Kotlin\r\n"
            + "*S Kotlin\r\n"
            + "*F\r\n"
            + "+ 1 Foo.kt\r\n"
            + "a/Foo\r\n"
            + "*L\r\n"
            + "x#1:5\r\n"
            + "1#1:\r\n"
            + "1#1,2\r\n"
            + "1#a:3\r\n"
            + "4#9:40\r\n"
            + "3#1,2:30\r\n"
            + "*O\r\n"
            + "*S Kotlin\r\n"
            + "*L\r\n"
            + "8#1:30\r\n"
            + "*C\r\n"
            + "*E\r\n";
    SourceMap map = parse(smap, "a/");

    assertEquals(1, map.fileCount());
    assertMaps(map, 30, 0, 3);
    assertMaps(map, 31, 0, 4);
    assertEquals(-1, map.map(5));
    assertEquals(-1, map.map(3));
    assertEquals(-1, map.map(40), "a range of a file the map does not name");
  }

  @Test
  void readsNoMapWhereLinesAreTheClassesOwn() {
    assertNull(SmapParser.parse(null, "a/"));
    assertNull(SmapParser.parse("not a source map", "a/"));
    assertNull(SmapParser.parse("SMAP\nFoo.java\nJava\n*E\n", "a/"));
  }

  private static SourceMap parse(String smap, String packageDirectory) {
    SourceMap map = SmapParser.parse(smap, packageDirectory);
    assertNotNull(map);
    return map;
  }

  private static void assertMaps(SourceMap map, int line, int fileIndex, int sourceLine) {
    assertEquals(((long) fileIndex << 32) | sourceLine, map.map(line), "line " + line);
  }
}
