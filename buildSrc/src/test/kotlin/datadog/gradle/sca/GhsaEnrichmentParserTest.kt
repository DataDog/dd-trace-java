package datadog.gradle.sca

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GhsaEnrichmentParserTest {

  private fun fixture(name: String): String =
      GhsaEnrichmentParserTest::class.java
          .getResourceAsStream("/sca/fixtures/$name")!!
          .bufferedReader()
          .readText()

  @Test
  fun `single package entry produces one record`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-single-package.json"))

    assertThat(entries).hasSize(1)
    val entry = entries[0]
    assertThat(entry["vuln_id"]).isEqualTo("GHSA-single-package")
    assertThat(entry["artifact"]).isEqualTo("com.fasterxml.jackson.core:jackson-databind")
    assertThat(entry["version_ranges"]).isEqualTo(listOf("< 2.6.7.3", ">= 2.7.0, < 2.7.9.5"))
  }

  @Test
  fun `ghsa id is read from vulnerability id field in json`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-single-package.json"))

    assertThat(entries[0]["vuln_id"]).isEqualTo("GHSA-single-package")
  }

  @Test
  fun `class names are converted to JVM internal format with slashes`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-single-package.json"))

    @Suppress("UNCHECKED_CAST")
    val symbols = entries[0]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols).hasSize(2)
    assertThat(symbols.map { it["class"] })
        .containsExactly(
            "com/fasterxml/jackson/databind/ObjectMapper",
            "com/fasterxml/jackson/databind/ObjectReader",
        )
  }

  @Test
  fun `method field is always non-null`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-single-package.json"))

    @Suppress("UNCHECKED_CAST")
    val symbols = entries[0]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols).allSatisfy { symbol -> assertThat(symbol["method"]).isNotNull() }
  }

  @Test
  fun `method name is extracted from target string`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-single-package.json"))

    @Suppress("UNCHECKED_CAST")
    val symbols = entries[0]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols.map { it["method"] }).containsExactly("readValue", "readValue")
  }

  @Test
  fun `multi-package entry produces one record per artifact`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-multi-package.json"))

    assertThat(entries).hasSize(2)
    assertThat(entries.map { it["artifact"] })
        .containsExactlyInAnyOrder(
            "org.springframework.boot:spring-boot-starter-web",
            "org.springframework:spring-webmvc",
        )
  }

  @Test
  fun `multi-package entries each have their own version ranges`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-multi-package.json"))

    val webEntry =
        entries.first { it["artifact"] == "org.springframework.boot:spring-boot-starter-web" }
    assertThat(webEntry["version_ranges"]).isEqualTo(listOf("< 2.5.12", ">= 2.6.0, < 2.6.6"))

    val mvcEntry = entries.first { it["artifact"] == "org.springframework:spring-webmvc" }
    assertThat(mvcEntry["version_ranges"])
        .isEqualTo(listOf(">= 5.3.0, < 5.3.18", "< 5.2.20.RELEASE"))
  }

  @Test
  fun `multi-package entries can share the same symbols`() {
    // In the new format each artifact entry is independent and may have its own targets.
    // This fixture has two entries with identical targets; verifying that each parses them correctly.
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-multi-package.json"))

    @Suppress("UNCHECKED_CAST")
    val symbols0 = entries[0]["symbols"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val symbols1 = entries[1]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols0.map { it["class"] })
        .containsExactlyInAnyOrder(
            "org/springframework/stereotype/Controller",
            "org/springframework/web/bind/annotation/RestController",
        )
    assertThat(symbols0.map { it["class"] }).isEqualTo(symbols1.map { it["class"] })
  }

  @Test
  fun `targets from different packages within one entry produce independent symbol entries`() {
    // Models GHSA-cwq5-8pvq-j65j (Zserio): a single entry has targets from zserio.runtime.array
    // and zserio.runtime.io (two different packages under the same artifact).
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-cross-package.json"))

    assertThat(entries).hasSize(1)
    assertThat(entries[0]["artifact"]).isEqualTo("io.github.ndsev:zserio-runtime")

    @Suppress("UNCHECKED_CAST")
    val symbols = entries[0]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols).hasSize(2)
    assertThat(symbols.map { it["class"] })
        .containsExactlyInAnyOrder(
            "zserio/runtime/array/Array",
            "zserio/runtime/io/ByteArrayBitStreamReader",
        )
    assertThat(symbols.map { it["method"] }).containsExactlyInAnyOrder("read", "readBitBuffer")
  }

  @Test
  fun `non-maven language entries are ignored`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-mixed-languages.json"))

    assertThat(entries).hasSize(1)
    assertThat(entries[0]["artifact"]).isEqualTo("com.thoughtworks.xstream:xstream")
  }

  @Test
  fun `entries with empty targets produce no output`() {
    val entries = GhsaEnrichmentParser.parse(fixture("GHSA-empty-symbols.json"))

    assertThat(entries).isEmpty()
  }

  @Test
  fun `targets without colon separator are silently skipped`() {
    val json =
        """[{
        "targets": ["noColonHere", "valid.pkg:Class.method"],
        "lang": "maven",
        "dependency_name": "com.example:lib",
        "package_versions": ["< 1.0.0"],
        "vulnerability": {"id": "GHSA-malformed-1", "severity": "LOW", "description": ""}
      }]"""
    val entries = GhsaEnrichmentParser.parse(json)

    assertThat(entries).hasSize(1)
    @Suppress("UNCHECKED_CAST")
    val symbols = entries[0]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols).hasSize(1)
    assertThat(symbols[0]["class"]).isEqualTo("valid/pkg/Class")
    assertThat(symbols[0]["method"]).isEqualTo("method")
  }

  @Test
  fun `targets without dot after colon are silently skipped`() {
    val json =
        """[{
        "targets": ["pkg:ClassWithNoMethod", "pkg:Class.method"],
        "lang": "maven",
        "dependency_name": "com.example:lib",
        "package_versions": ["< 1.0.0"],
        "vulnerability": {"id": "GHSA-malformed-2", "severity": "LOW", "description": ""}
      }]"""
    val entries = GhsaEnrichmentParser.parse(json)

    assertThat(entries).hasSize(1)
    @Suppress("UNCHECKED_CAST")
    val symbols = entries[0]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols).hasSize(1)
    assertThat(symbols[0]["method"]).isEqualTo("method")
  }

  @Test
  fun `entry with all malformed targets produces no output`() {
    val json =
        """[{
        "targets": ["noColon", "alsoNoColon"],
        "lang": "maven",
        "dependency_name": "com.example:lib",
        "package_versions": ["< 1.0.0"],
        "vulnerability": {"id": "GHSA-all-malformed", "severity": "LOW", "description": ""}
      }]"""
    val entries = GhsaEnrichmentParser.parse(json)

    assertThat(entries).isEmpty()
  }

  @Test
  fun `non-json-array input throws IllegalArgumentException`() {
    assertThatThrownBy { GhsaEnrichmentParser.parse("""{"lang": "maven"}""") }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("must be a JSON array")
  }
}
