package datadog.gradle.sca

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Parses GHSA symbol JSON files from the sca-reachability-symbols repository into the internal
 * sca_cves.json format consumed by SCA Reachability at runtime.
 *
 * Key transformations:
 * - Filters entries to Maven ecosystem only (lang == "maven")
 * - Each array entry maps 1:1 to one sca_cves.json record (one dependency_name per entry)
 * - Parses target strings "package:ClassName.method" using lastIndexOf to split at the colon
 *   and lastIndexOf on the class+method part to split class from method
 * - Converts class FQNs to JVM internal format (slashes) so the ClassFileTransformer
 *   can do O(1) map lookups without per-class string conversion at runtime
 */
object GhsaEnrichmentParser {

  private val mapper = ObjectMapper()

  /**
   * Parses a single GHSA symbols file.
   *
   * @param jsonContent the raw JSON content of the symbols file
   * @return list of sca_cves.json entry maps, one per affected Maven artifact
   */
  fun parse(jsonContent: String): List<Map<String, Any?>> {
    val root = mapper.readTree(jsonContent)
    require(root.isArray) { "GHSA enrichment file must be a JSON array, got ${root.nodeType}" }

    val entries = mutableListOf<Map<String, Any?>>()

    for (entry in root) {
      if (entry.path("lang").asText() != "maven") continue

      val ghsaId =
          entry.path("vulnerability").path("id").asText().takeIf { it.isNotEmpty() } ?: continue
      val artifact = entry.path("dependency_name").asText().takeIf { it.isNotEmpty() } ?: continue
      val versionRanges = entry.path("package_versions").map { it.asText() }

      val symbols = extractTargets(entry)
      if (symbols.isEmpty()) continue

      entries +=
          mapOf(
              "vuln_id" to ghsaId,
              "artifact" to artifact,
              "version_ranges" to versionRanges,
              "symbols" to symbols,
          )
    }

    return entries
  }

  /**
   * Parses the targets array from a GHSA entry.
   *
   * Each target string has the format "package:ClassName.method". Parsing uses
   * lastIndexOf(':') to split package from class+method, then lastIndexOf('.') on the
   * class+method part to split class name from method name. Malformed targets (missing ':'
   * or missing '.' after ':') are silently skipped.
   *
   * Targets within one entry may come from different packages; no assumption is made that
   * all targets share a common package prefix.
   *
   * TODO(APPSEC-62260): if the database adds inner-class targets (e.g. "pkg:Outer.Inner.method"),
   * the current replace('.', '/') will produce pkg/Outer/Inner instead of the correct
   * pkg/Outer$Inner. Update when the database team defines the inner-class format.
   */
  private fun extractTargets(entry: JsonNode): List<Map<String, Any?>> {
    val symbols = mutableListOf<Map<String, Any?>>()
    val targets = entry.path("targets")
    if (targets.isMissingNode || !targets.isArray) return symbols

    for (target in targets) {
      val t = target.asText().takeIf { it.isNotEmpty() } ?: continue
      val colonIdx = t.lastIndexOf(':')
      if (colonIdx < 0) continue
      val pkg = t.substring(0, colonIdx)
      val classAndMethod = t.substring(colonIdx + 1)
      val dotIdx = classAndMethod.lastIndexOf('.')
      if (dotIdx < 0) continue
      val simpleClass = classAndMethod.substring(0, dotIdx)
      val method = classAndMethod.substring(dotIdx + 1)
      if (pkg.isEmpty() || simpleClass.isEmpty() || method.isEmpty()) continue

      val internalName = "$pkg.$simpleClass".replace('.', '/')
      symbols += mapOf("class" to internalName, "method" to method)
    }

    return symbols
  }
}
