package datadog.gradle.plugin.tags

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import java.util.Locale

/**
 * Turns the language-agnostic {@code tag-conventions.yaml} + the Java overlay into the generated tag
 * registry: {@code KnownTags.java} (under {@code java/<pkg>}) plus verification report dumps
 * (resolved-tags / tag-assignment / layout-by-type / folded-types) at the destination root.
 *
 * Pure function of its inputs (deterministic ordering throughout), so the same inputs always produce
 * byte-identical output -- which is what the {@code verifyKnownTags} freshness gate relies on.
 */
object TagRegistryGenerator {
  /** Parses the two YAML files and writes the full generated tree under [outDir]. */
  fun generate(domainYaml: File, overlayYaml: File, outDir: File) {
    val mapper = ObjectMapper(YAMLFactory())
    val domain: Map<String, Any?> =
      domainYaml.inputStream().use {
        mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {})
      }
    val overlayMap: Map<String, Any?> =
      overlayYaml.inputStream().use {
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
    val overlay = TagRegistry.Overlay.parse(overlayMap)
    val reg = TagRegistry.build(conv, overlay)

    File(outDir, "resolved-tags.txt").writeText(resolvedReport(conv))
    File(outDir, "tag-assignment.txt").writeText(assignmentReport(conv, reg))
    File(outDir, "layout-by-type.txt").writeText(layoutByTypeReport(conv, reg))
    File(outDir, "folded-types.txt").writeText(foldedTypesReport(conv, reg))
    File(javaPkg, "KnownTags.java")
      .writeText(KnownTagsEmitter.emit(reg, "datadog.trace.api", "KnownTags"))
  }

  /** resolved-tags.txt — the per-type resolved sets (composition check). */
  private fun resolvedReport(conv: TagConventions): String {
    val resolved = StringBuilder()
    resolved.appendLine("# Resolved per-type tag sets (concrete span types).")
    for (type in conv.concreteTypes()) {
      val tags = conv.resolve(type)
      resolved.appendLine()
      resolved.appendLine("$type  (${tags.size} tags):")
      for (t in tags) resolved.appendLine("  - ${t.name}")
    }
    return resolved.toString()
  }

  /** tag-assignment.txt — serials, colored slots, ids, per-type slot sets (coloring check). */
  private fun assignmentReport(conv: TagConventions, reg: TagRegistry): String {
    val byName = reg.stored.associateBy { it.name }
    val a = StringBuilder()
    a.appendLine(
      "# Tag id assignment.  slotCount=${reg.slotCount}  stored=${reg.stored.size}  reserved=${reg.reserved.size}")
    a.appendLine()
    a.appendLine("# STORED   serial  slot int lvl id                 required     name")
    for (t in reg.stored) {
      a.appendLine(
        "  %6d %5s  %s   %s  %-18s %-12s %s".format(
          Locale.ROOT,
          t.serial,
          if (t.slotted) t.slot.toString() else "-",
          if (t.intercepted) "I" else "-",
          if (t.traceLevel) "T" else "-",
          "0x%016X".format(Locale.ROOT, t.id),
          t.required,
          t.name))
    }
    a.appendLine()
    a.appendLine("# RESERVED serial       id                 kind         name")
    for (v in reg.reserved) {
      a.appendLine(
        "  %6d      %-18s %-12s %s%s".format(
          Locale.ROOT,
          v.serial,
          "0x%016X".format(Locale.ROOT, v.id),
          v.kind,
          v.name,
          v.field?.let { " -> $it" } ?: ""))
    }
    a.appendLine()
    a.appendLine("# PER-TYPE colored slots. Slots within a type must be DISTINCT (a valid coloring of the")
    a.appendLine("# co-occurrence clique); <trace> is its own clique and freely reuses span slot numbers.")
    for (type in conv.concreteTypes()) {
      val slots =
        conv.resolve(type).mapNotNull { byName[it.name] }
          .filter { it.slotted && !it.traceLevel }
          .map { it.slot }
          .sorted()
      a.appendLine("  %-14s count=%-3d slots=%s".format(Locale.ROOT, type, slots.size, slots))
    }
    val traceSlots =
      reg.stored.filter { it.traceLevel && it.slotted }.map { it.slot }.sorted()
    a.appendLine(
      "  %-14s count=%-3d slots=%s".format(Locale.ROOT, "<trace>", traceSlots.size, traceSlots))
    a.appendLine()
    a.appendLine("# OPENTELEMETRY NAMES. keyOf(otelName) resolves to the canonical tag's id; nameOf still")
    a.appendLine("# returns the Datadog name, openTelemetryNameOf returns the name below. (No distinct id.)")
    val otelPairs =
      (reg.stored.mapNotNull { t -> t.otelName?.let { it to t.name } } +
          reg.reserved.mapNotNull { v -> v.otelName?.let { it to v.name } })
        .sortedBy { it.first }
    for ((otel, canonical) in otelPairs) {
      a.appendLine("  %-30s -> %s".format(Locale.ROOT, otel, canonical))
    }
    return a.toString()
  }

  /**
   * layout-by-type.txt — full composition per type (origins shown, NOT de-duped), each tag annotated
   * with its slot/tier: s<n> = colored slot, trace s<n> = trace-level layer, bkt = bucket.
   */
  private fun layoutByTypeReport(conv: TagConventions, reg: TagRegistry): String {
    val byName = reg.stored.associateBy { it.name }
    val lay = StringBuilder()
    lay.appendLine("# Full tag composition per concrete span type (after extends/include/applies).")
    lay.appendLine("# Not de-duped: a tag from >1 source appears >1 time.")
    lay.appendLine("# annotation: [s<n> colored slot | trace s<n> trace layer | bkt bucketed]  <required>  I=intercepted")
    for (type in conv.concreteTypes()) {
      val comp = conv.compose(type)
      val distinct = comp.map { it.second.name }.distinct().size
      lay.appendLine()
      lay.appendLine("$type  (${comp.size} contributions, $distinct distinct):")
      val byOrigin = LinkedHashMap<String, MutableList<TagConventions.Tag>>()
      for ((origin, tag) in comp) byOrigin.getOrPut(origin) { ArrayList() }.add(tag)
      for ((origin, tags) in byOrigin) {
        lay.appendLine("  [$origin]")
        for (t in tags) {
          val st = byName[t.name]
          val field =
            when {
              st == null -> "?"
              st.traceLevel && st.slotted -> "trace s${st.slot}"
              st.slotted -> "s${st.slot}"
              else -> "bkt"
            }
          lay.appendLine(
            "    %-26s %-12s %-12s %s".format(
              Locale.ROOT, t.name, field, t.required, if (st?.intercepted == true) "I" else ""))
        }
      }
    }
    return lay.toString()
  }

  /**
   * folded-types.txt — each type's full resolved set (extends + include + applies, DE-DUPED) with its
   * slot; plus the <trace> type. This is the "type with everything folded in" view.
   */
  private fun foldedTypesReport(conv: TagConventions, reg: TagRegistry): String {
    val byName = reg.stored.associateBy { it.name }
    fun tierField(st: TagRegistry.StoredTag?): String =
      when {
        st == null -> "?"
        st.traceLevel -> if (st.slotted) "trace s${st.slot}" else "trace-bkt"
        st.slotted -> "s${st.slot}"
        else -> "bkt"
      }
    val f = StringBuilder()
    f.appendLine("# Folded tag set per type (extends + include + applies, de-duped), with colored slots.")
    f.appendLine(
      "# field: s<n>=colored slot  trace s<n>=trace-level layer  bkt=bucketed  trace-bkt=trace-level bucketed  I=intercepted")
    for (type in conv.concreteTypes()) {
      val tags = conv.resolve(type)
      f.appendLine()
      f.appendLine("$type  (${tags.size} tags):")
      for (t in tags) {
        val st = byName[t.name]
        f.appendLine(
          "  %-12s %-26s %s".format(
            Locale.ROOT, tierField(st), t.name, if (st?.intercepted == true) "I" else ""))
      }
    }
    val traceTags =
      reg.stored.filter { it.traceLevel }.sortedWith(compareBy({ !it.slotted }, { it.slot }, { it.name }))
    f.appendLine()
    f.appendLine("<trace>  (${traceTags.size} tags):")
    for (st in traceTags) {
      f.appendLine(
        "  %-12s %-26s %s".format(
          Locale.ROOT, tierField(st), st.name, if (st.intercepted) "I" else ""))
    }
    return f.toString()
  }
}
