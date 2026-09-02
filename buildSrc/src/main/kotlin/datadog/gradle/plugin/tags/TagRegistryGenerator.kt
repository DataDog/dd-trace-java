package datadog.gradle.plugin.tags

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import java.util.Locale

/**
 * Turns the language-agnostic {@code tag-conventions.yaml} into the generated tag registry: {@code KnownTags.java} (under {@code java/<pkg>}) plus verification report dumps
 * (resolved-tags / tag-assignment) at the destination root.
 *
 * Pure function of its inputs (deterministic ordering throughout), so the same inputs always produce
 * byte-identical output -- which is what the {@code verifyKnownTags} freshness gate relies on.
 */
object TagRegistryGenerator {
  /** Parses the conventions YAML and writes the full generated tree under [outDir]. */
  fun generate(domainYaml: File, outDir: File) {
    val mapper = ObjectMapper(YAMLFactory())
    val domain: Map<String, Any?> =
      domainYaml.inputStream().use {
        mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {})
      }

    // Clear the owned destination tree first, so a report/source file retired by a later generator
    // revision doesn't linger: otherwise verifyKnownTags flags it as stale while telling developers
    // to rerun generateKnownTags, which (without this) can't actually remove it.
    outDir.deleteRecursively()
    outDir.mkdirs()
    // KnownTags.java goes under java/<pkg> (added as a srcDir); the .txt reports sit at the root.
    val javaPkg = File(outDir, "java/datadog/trace/api").apply { mkdirs() }

    val conv = TagConventions.parse(domain)
    val reg = TagRegistry.build(conv)

    File(outDir, "resolved-tags.txt").writeText(resolvedReport(conv))
    File(outDir, "tag-assignment.txt").writeText(assignmentReport(reg))
    File(javaPkg, "KnownTags.java")
      .writeText(KnownTagsEmitter.emit(reg, "datadog.trace.api", "KnownTags"))
  }

  /** resolved-tags.txt — the per-type resolved sets (composition check). */
  private fun resolvedReport(conv: TagConventions): String {
    val resolved = StringBuilder()
    resolved.appendLine("# Resolved per-type tag sets (concrete span types).")
    val unmodeled = conv.unmodeledAppliesTargets()
    if (unmodeled.isNotEmpty()) {
      resolved.appendLine("#")
      resolved.appendLine("# LAYOUT GAP: these mixins apply to span types not modeled here, so they")
      resolved.appendLine("# contribute to no resolved set below. Their tags ARE registered (an id is")
      resolved.appendLine("# identity, not layout) -- they simply occupy no per-type slot yet.")
      for ((mixin, missing) in unmodeled) {
        resolved.appendLine("#   $mixin -> ${missing.joinToString(", ")}")
      }
    }
    for (type in conv.concreteTypes()) {
      val tags = conv.resolve(type)
      resolved.appendLine()
      resolved.appendLine("$type  (${tags.size} tags):")
      for (t in tags) resolved.appendLine("  - ${t.name}")
    }
    return resolved.toString()
  }

  /** tag-assignment.txt — serials, ids, and the OpenTelemetry name mapping (identity check). */
  private fun assignmentReport(reg: TagRegistry): String {
    val a = StringBuilder()
    a.appendLine("# Tag id assignment.  tags=${reg.tags.size}")
    a.appendLine()
    a.appendLine("# TAGS     serial lvl id                 required     name")
    for (t in reg.tags) {
      a.appendLine(
        "  %6d   %s  %-18s %-12s %s".format(
          Locale.ROOT,
          t.serial,
          if (t.traceLevel) "T" else "-",
          "0x%016X".format(Locale.ROOT, t.id),
          t.required,
          t.name))
    }
    a.appendLine()
    a.appendLine("# OPENTELEMETRY NAMES. keyOf(otelName) resolves to the canonical tag's id; nameOf still")
    a.appendLine("# returns the Datadog name, openTelemetryNameOf returns the name below. (No distinct id.)")
    val otelPairs = reg.tags.mapNotNull { t -> t.otelName?.let { it to t.name } }.sortedBy { it.first }
    for ((otel, canonical) in otelPairs) {
      a.appendLine("  %-30s -> %s".format(Locale.ROOT, otel, canonical))
    }
    return a.toString()
  }
}
