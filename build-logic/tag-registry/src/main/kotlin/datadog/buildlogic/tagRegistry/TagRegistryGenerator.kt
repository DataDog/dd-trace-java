package datadog.buildlogic.tagRegistry

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import java.util.Locale

/** Emits the Java tag registry and reports in a deterministic order. */
object TagRegistryGenerator {
  /**
   * Parses the conventions YAML, plus the tracer overlay when there is one, and writes the full
   * generated tree under [outDir].
   */
  fun generate(tagConventionsFile: File, outDir: File, tracerOverlayFile: File? = null) {
    val mapper = ObjectMapper(YAMLFactory())
    fun read(file: File): Map<String, Any?> =
      file.inputStream().use { mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) } ?: emptyMap()
    val domain = read(tagConventionsFile)
    val overlay = tracerOverlayFile?.let(::read) ?: emptyMap()

    // Validate before touching the destination tree: an invalid domain model must fail loudly,
    // not after the previous (valid) generated output has already been wiped out.
    val conv = TagConventions.parse(domain, overlay)
    val reg = TagRegistry.build(conv)

    // Remove obsolete generated files when the output changes.
    outDir.deleteRecursively()
    outDir.mkdirs()
    // KnownTags.java goes under java/<pkg> (added as a srcDir); the .txt reports sit at the root.
    val javaPkg = File(outDir, "java/datadog/trace/api").apply { mkdirs() }

    File(outDir, "resolved-tags.txt").writeText(resolvedReport(conv))
    File(outDir, "tag-assignment.txt").writeText(assignmentReport(reg))
    File(javaPkg, "KnownTags.java")
      .writeText(KnownTagsEmitter.emit(reg, "datadog.trace.api", "KnownTags"))
  }

  /** resolved-tags.txt — the per-type resolved sets (composition check). */
  private fun resolvedReport(conv: TagConventions) = buildString {
    appendLine("# Resolved per-type tag sets (concrete span types).")
    val unmodeled = conv.unmodeledAppliesTargets()
    if (unmodeled.isNotEmpty()) {
      appendLine(
        """
        #
        # LAYOUT GAP: these mixins apply to span types not modeled here, so they
        # contribute to no resolved set below. Their tags ARE registered (an id is
        # identity, not layout) -- they simply occupy no per-type slot yet.
        """.trimIndent()
      )
      for ((mixin, missing) in unmodeled) {
        appendLine("#   $mixin -> ${missing.joinToString(", ")}")
      }
    }
    for (type in conv.concreteTypes()) {
      val tags = conv.resolve(type)
      appendLine(
        """

        $type  (${tags.size} tags):
        """.trimIndent()
      )
      for (t in tags) appendLine("  - ${t.name}")
    }
  }

  /** tag-assignment.txt — serials, ids, and the OpenTelemetry name mapping (identity check). */
  private fun assignmentReport(reg: TagRegistry) = buildString {
    appendLine(
      """
      # Tag id assignment.  tags=${reg.tags.size}

      # TAGS     serial lvl int id                 required     name
      """.trimIndent()
    )
    for (t in reg.tags) {
      appendLine(
        "  %6d   %s   %s   %-18s %-12s %s".format(
          Locale.ROOT,
          t.serial,
          if (t.traceLevel) "T" else "-",
          if (t.intercepted) "I" else "-",
          "0x%016X".format(Locale.ROOT, t.id),
          t.required,
          t.name
        )
      )
    }
    appendLine(
      """

      # OPENTELEMETRY NAMES. keyOf(otelName) resolves to the canonical tag's id; nameOf still
      # returns the Datadog name, openTelemetryNameOf returns the name below. (No distinct id.)
      """.trimIndent()
    )
    val otelPairs = reg.tags.mapNotNull { t -> t.otelName?.let { it to t.name } }.sortedBy { it.first }
    for ((otel, canonical) in otelPairs) {
      appendLine("  %-30s -> %s".format(Locale.ROOT, otel, canonical))
    }
    appendLine(
      """

      # DIRECTION-SCOPED OPENTELEMETRY NAMES. Each applies only on spans of the given direction, so
      # it is not in the tables above; name resolution does not use it until it knows the direction.
      """.trimIndent()
    )
    val scoped =
      reg.tags.filter { it.otelDirection != null }.sortedWith(compareBy({ it.declaredOtelName }, { it.otelDirection }))
    for (t in scoped) {
      appendLine("  %-30s %-9s -> %s".format(Locale.ROOT, t.declaredOtelName, t.otelDirection!!.yamlKey, t.name))
    }
    appendLine(
      """

      # SHARED DATADOG NAMES. One Datadog name for a tag per direction: emitting it needs no context,
      # but resolving the name to a tag needs the span's direction, so keyOf does not resolve it yet.
      """.trimIndent()
    )
    for ((ddName, shared) in reg.tags.filter { it.sharedNameDirection != null }.groupBy { it.ddName }.toSortedMap()) {
      appendLine("  %-30s -> %s".format(Locale.ROOT, ddName, shared.joinToString(", ") { it.name }))
    }
  }
}
