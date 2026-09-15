package datadog.gradle.plugin.tags

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import java.util.Locale

/**
 * Turns the language-agnostic {@code tag-conventions.yaml}, plus this language's routing overlay
 * {@code tag-conventions-java.yaml}, into the generated tag registry: {@code KnownTags.java} (under
 * {@code java/<pkg>}) plus verification report dumps (resolved-tags / tag-assignment) at the
 * destination root.
 *
 * Pure function of its inputs (deterministic ordering throughout), so the same inputs always produce
 * byte-identical output -- which is what the {@code verifyKnownTags} freshness gate relies on.
 */
object TagRegistryGenerator {
  /**
   * Parses the conventions YAML plus the routing overlay and writes the full generated tree under
   * [outDir]. [overlayYaml] is optional: a language with no reserved keys passes null and gets the
   * domain registry alone, byte-identical to what it would get without an overlay at all.
   */
  fun generate(domainYaml: File, overlayYaml: File?, outDir: File) {
    val mapper = ObjectMapper(YAMLFactory())
    fun readYaml(f: File): Map<String, Any?> =
      f.inputStream().use { mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }

    val domain: Map<String, Any?> = readYaml(domainYaml)
    val overlay =
      if (overlayYaml == null) TagOverlay.empty() else TagOverlay.parse(readYaml(overlayYaml))

    // Clear the owned destination tree first, so a report/source file retired by a later generator
    // revision doesn't linger: otherwise verifyKnownTags flags it as stale while telling developers
    // to rerun generateKnownTags, which (without this) can't actually remove it.
    outDir.deleteRecursively()
    outDir.mkdirs()
    // KnownTags.java goes under java/<pkg> (added as a srcDir); the .txt reports sit at the root.
    val javaPkg = File(outDir, "java/datadog/trace/api").apply { mkdirs() }

    val conv = TagConventions.parse(domain)
    val reg = TagRegistry.build(conv, overlay)

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

  /**
   * tag-assignment.txt — serials, ids, and the OpenTelemetry name mapping (identity check). Domain
   * tags and the overlay's reserved keys are reported as separate blocks, in serial order, because
   * that is the invariant worth being able to eyeball: every domain serial precedes every reserved
   * one, so a change to the overlay cannot renumber the domain block.
   */
  private fun assignmentReport(reg: TagRegistry): String {
    val a = StringBuilder()
    a.appendLine("# Tag id assignment.  tags=${reg.tags.size}")
    a.appendLine()
    val row = { t: TagRegistry.Tag ->
      "  %6d   %s  %-18s %-12s %s".format(
        Locale.ROOT,
        t.serial,
        if (t.traceLevel) "T" else "-",
        "0x%016X".format(Locale.ROOT, t.id),
        t.required,
        t.name)
    }
    val (reserved, domain) = reg.tags.partition { it.overlay }
    a.appendLine("# TAGS     serial lvl id                 required     name")
    for (t in domain) a.appendLine(row(t))
    if (reserved.isNotEmpty()) {
      a.appendLine()
      a.appendLine("# RESERVED KEYS (java overlay). Set-path routing identities: accepted by setTag but")
      a.appendLine("# diverted to a span field or a trace directive. They have no `required` grade (that")
      a.appendLine("# grades storage) and belong to no span type's resolved set. Serials continue after")
      a.appendLine("# the domain block, so adding one cannot renumber the tags above.")
      for (t in reserved) a.appendLine(row(t))
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
