package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integration tests that load the real {@code sca_cves.json} and verify the three library classes
 * used to validate the new sca-reachability-symbols database format:
 *
 * <ul>
 *   <li>{@code com.github.junrar:junrar 7.5.5} - GHSA-hf5p-q87m-crj7
 *   <li>{@code io.github.ndsev:zserio-runtime 2.16.1} - GHSA-cwq5-8pvq-j65j
 *   <li>{@code org.apache.tomcat.embed:tomcat-embed-core 9.0.115} - GHSA-563x-q5rq-57qp
 * </ul>
 */
class ScaRealDatabaseTest {

  @Test
  void junrarLocalFolderExtractorMethodsAreIndexed() {
    ScaCveDatabase db = ScaCveDatabase.load();

    List<ScaEntry> entries = db.entriesForClass("com/github/junrar/LocalFolderExtractor");
    assertNotNull(entries, "LocalFolderExtractor must be indexed (junrar GHSA-hf5p)");
    assertFalse(entries.isEmpty());
    assertTrue(
        entries.stream()
            .flatMap(e -> e.symbols().stream())
            .anyMatch(s -> "createDirectory".equals(s.method())),
        "createDirectory must be a tracked method");
    assertTrue(
        entries.stream()
            .flatMap(e -> e.symbols().stream())
            .anyMatch(s -> "createFile".equals(s.method())),
        "createFile must be a tracked method");
  }

  @Test
  void zserioArrayReadIsIndexed() {
    ScaCveDatabase db = ScaCveDatabase.load();

    List<ScaEntry> entries = db.entriesForClass("zserio/runtime/array/Array");
    assertNotNull(entries, "zserio Array must be indexed (zserio-runtime GHSA-cwq5)");
    assertFalse(entries.isEmpty());
    assertTrue(
        entries.stream()
            .flatMap(e -> e.symbols().stream())
            .anyMatch(s -> "read".equals(s.method())),
        "read must be a tracked method");
  }

  @Test
  void tomcatChunkedInputFilterIsIndexed() {
    ScaCveDatabase db = ScaCveDatabase.load();

    List<ScaEntry> entries =
        db.entriesForClass("org/apache/coyote/http11/filters/ChunkedInputFilter");
    assertNotNull(entries, "ChunkedInputFilter must be indexed (tomcat-embed-core GHSA-563x)");
    assertFalse(entries.isEmpty());
    assertTrue(
        entries.stream()
            .flatMap(e -> e.symbols().stream())
            .anyMatch(s -> "parseChunkHeader".equals(s.method())),
        "parseChunkHeader must be a tracked method");
  }

  @Test
  void allTrackedSymbolsForThreeLibrariesAreMethodLevel() {
    ScaCveDatabase db = ScaCveDatabase.load();

    String[] classes = {
      "com/github/junrar/LocalFolderExtractor",
      "zserio/runtime/array/Array",
      "zserio/runtime/io/ByteArrayBitStreamReader",
      "org/apache/coyote/http11/filters/ChunkedInputFilter",
    };
    for (String className : classes) {
      List<ScaEntry> entries = db.entriesForClass(className);
      if (entries == null) continue;
      for (ScaEntry entry : entries) {
        for (ScaSymbol symbol : entry.symbols()) {
          assertNotNull(
              symbol.method(),
              "All symbols must be method-level - found null method for "
                  + symbol.className()
                  + " in entry "
                  + entry.vulnId());
        }
      }
    }
  }
}
