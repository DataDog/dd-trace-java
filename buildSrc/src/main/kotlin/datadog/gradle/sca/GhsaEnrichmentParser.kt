package datadog.gradle.sca

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Parses GHSA enrichment JSON files from the sca-reachability-database into the internal
 * sca_cves.json format consumed by SCA Reachability at runtime.
 *
 * Key transformations:
 * - Filters entries to JVM language only
 * - Expands multi-package GHSA entries into N records (one per Maven artifact), because
 *   each artifact may have different version ranges for the same set of class symbols
 * - Converts class FQNs to JVM internal format (slashes) so the ClassFileTransformer
 *   can do O(1) map lookups without per-class string conversion
 * - Sets method=null for all symbols — field exists for forward compatibility when the
 *   database adds method-level symbols in the future (see APPSEC-62260)
 */
object GhsaEnrichmentParser {

  private val mapper = ObjectMapper()

  /**
   * Parses a single GHSA enrichment file.
   *
   * @param ghsaId the GHSA identifier (e.g. "GHSA-645p-88qh-w398"), used as vuln_id
   * @param jsonContent the raw JSON content of the enrichment file
   * @return list of sca_cves.json entry maps, one per affected Maven artifact
   */
  fun parse(ghsaId: String, jsonContent: String): List<Map<String, Any?>> {
    val root = mapper.readTree(jsonContent)
    require(root.isArray) { "GHSA enrichment file $ghsaId must be a JSON array, got ${root.nodeType}" }

    val entries = mutableListOf<Map<String, Any?>>()

    for (entry in root) {
      if (entry.path("language").asText() != "jvm") continue

      val symbols = extractSymbols(entry)
      if (symbols.isEmpty()) continue

      for (pkg in entry.path("package")) {
        if (pkg.path("ecosystem").asText() != "maven") continue
        val artifact = pkg.path("name").asText().takeIf { it.isNotEmpty() } ?: continue
        val versionRanges = pkg.path("version_range").map { it.asText() }

        entries += mapOf(
          "vuln_id" to ghsaId,
          "artifact" to artifact,
          "version_ranges" to versionRanges,
          "symbols" to symbols,
        )
      }
    }

    return entries
  }

  private fun extractSymbols(entry: JsonNode): List<Map<String, Any?>> {
    val symbols = mutableListOf<Map<String, Any?>>()
    val imports = entry.path("ecosystem_specific").path("imports")
    if (imports.isMissingNode || !imports.isArray) return symbols

    for (importGroup in imports) {
      for (symbol in importGroup.path("symbols")) {
        if (symbol.path("type").asText() != "class") continue
        val pkg = symbol.path("value").asText().takeIf { it.isNotEmpty() } ?: continue
        val name = symbol.path("name").asText().takeIf { it.isNotEmpty() } ?: continue

        // JVM internal format (slashes) — avoids per-class conversion in the
        // ClassFileTransformer hot path at runtime
        val internalName = "$pkg.$name".replace('.', '/')
        symbols += mapOf("class" to internalName, "method" to null)
      }
    }

    return symbols
  }
}
