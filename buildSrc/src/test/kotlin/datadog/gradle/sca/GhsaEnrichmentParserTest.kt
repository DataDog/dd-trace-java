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
    val entries = GhsaEnrichmentParser.parse("GHSA-single-package", fixture("GHSA-single-package.json"))

    assertThat(entries).hasSize(1)
    val entry = entries[0]
    assertThat(entry["vuln_id"]).isEqualTo("GHSA-single-package")
    assertThat(entry["artifact"]).isEqualTo("com.fasterxml.jackson.core:jackson-databind")
    assertThat(entry["version_ranges"]).isEqualTo(listOf("< 2.6.7.3", ">= 2.7.0, < 2.7.9.5"))
  }

  @Test
  fun `class names are converted to JVM internal format with slashes`() {
    val entries = GhsaEnrichmentParser.parse("GHSA-single-package", fixture("GHSA-single-package.json"))

    @Suppress("UNCHECKED_CAST")
    val symbols = entries[0]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols).hasSize(2)
    assertThat(symbols.map { it["class"] }).containsExactly(
      "com/fasterxml/jackson/databind/ObjectMapper",
      "com/fasterxml/jackson/databind/ObjectReader",
    )
  }

  @Test
  fun `method field is always null for class-level symbols`() {
    val entries = GhsaEnrichmentParser.parse("GHSA-single-package", fixture("GHSA-single-package.json"))

    @Suppress("UNCHECKED_CAST")
    val symbols = entries[0]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols).allSatisfy { symbol ->
      assertThat(symbol["method"]).isNull()
    }
  }

  @Test
  fun `multi-package entry expands to one record per artifact`() {
    val entries = GhsaEnrichmentParser.parse("GHSA-multi-package", fixture("GHSA-multi-package.json"))

    assertThat(entries).hasSize(2)
    assertThat(entries.map { it["artifact"] }).containsExactlyInAnyOrder(
      "org.springframework.boot:spring-boot-starter-web",
      "org.springframework:spring-webmvc",
    )
  }

  @Test
  fun `multi-package entries each have their own version ranges`() {
    val entries = GhsaEnrichmentParser.parse("GHSA-multi-package", fixture("GHSA-multi-package.json"))

    val webEntry = entries.first { it["artifact"] == "org.springframework.boot:spring-boot-starter-web" }
    assertThat(webEntry["version_ranges"]).isEqualTo(listOf("< 2.5.12", ">= 2.6.0, < 2.6.6"))

    val mvcEntry = entries.first { it["artifact"] == "org.springframework:spring-webmvc" }
    assertThat(mvcEntry["version_ranges"]).isEqualTo(listOf(">= 5.3.0, < 5.3.18", "< 5.2.20.RELEASE"))
  }

  @Test
  fun `multi-package entries share the same symbols`() {
    val entries = GhsaEnrichmentParser.parse("GHSA-multi-package", fixture("GHSA-multi-package.json"))

    @Suppress("UNCHECKED_CAST")
    val symbols0 = entries[0]["symbols"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val symbols1 = entries[1]["symbols"] as List<Map<String, Any?>>
    assertThat(symbols0.map { it["class"] }).containsExactlyInAnyOrder(
      "org/springframework/stereotype/Controller",
      "org/springframework/web/bind/annotation/RestController",
    )
    assertThat(symbols0.map { it["class"] }).isEqualTo(symbols1.map { it["class"] })
  }

  @Test
  fun `non-jvm language entries are ignored`() {
    val entries = GhsaEnrichmentParser.parse("GHSA-mixed-languages", fixture("GHSA-mixed-languages.json"))

    assertThat(entries).hasSize(1)
    assertThat(entries[0]["artifact"]).isEqualTo("com.thoughtworks.xstream:xstream")
  }

  @Test
  fun `entries with no symbols produce no output`() {
    val entries = GhsaEnrichmentParser.parse("GHSA-empty-symbols", fixture("GHSA-empty-symbols.json"))

    assertThat(entries).isEmpty()
  }

  @Test
  fun `ghsa id is used as vuln_id without modification`() {
    val ghsaId = "GHSA-645p-88qh-w398"
    val entries = GhsaEnrichmentParser.parse(ghsaId, fixture("GHSA-single-package.json"))

    assertThat(entries[0]["vuln_id"]).isEqualTo(ghsaId)
  }

  @Test
  fun `non-json-array input throws IllegalArgumentException`() {
    assertThatThrownBy {
      GhsaEnrichmentParser.parse("GHSA-bad", """{"language": "jvm"}""")
    }.isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("must be a JSON array")
  }
}
