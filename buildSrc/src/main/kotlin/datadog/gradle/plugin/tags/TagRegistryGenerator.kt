package datadog.gradle.plugin.tags

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

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

  /** tag-assignment.txt — serials, slots (coloring), ids, per-type packed sizes (assignment check). */
  private fun assignmentReport(conv: TagConventions, reg: TagRegistry): String {
    val byName = reg.stored.associateBy { it.name }
    val a = StringBuilder()
    a.appendLine(
      "# Tag id assignment.  slotCount=${reg.slotCount}  stored=${reg.stored.size}  virtual=${reg.virtuals.size}")
    a.appendLine()
    a.appendLine("# STORED   serial slot int id                 required     name")
    for (t in reg.stored) {
      a.appendLine(
        "  %6d %4s  %s  %-18s %-12s %s".format(
          t.serial,
          if (t.slotted) t.slot.toString() else "-",
          if (t.intercepted) "I" else "-",
          "0x%016X".format(t.id),
          t.required,
          t.name))
    }
    a.appendLine()
    a.appendLine("# VIRTUAL  serial       id                 kind         name")
    for (v in reg.virtuals) {
      a.appendLine(
        "  %6d      %-18s %-12s %s%s".format(
          v.serial, "0x%016X".format(v.id), v.kind, v.name, v.field?.let { " -> $it" } ?: ""))
    }
    a.appendLine()
    a.appendLine("# PER-TYPE packed size (= max slot + 1).  <trace> = the trace-level TagMap layer.")
    for (type in conv.concreteTypes()) {
      val slots =
        conv.resolve(type).mapNotNull { byName[it.name] }
          .filter { it.slotted && !it.traceLevel }
          .map { it.slot }
          .sorted()
      val size = (slots.maxOrNull() ?: -1) + 1
      a.appendLine("  %-14s size=%-3d slots=%s".format(type, size, slots))
    }
    val traceSlots = reg.stored.filter { it.traceLevel && it.slotted }.map { it.slot }.sorted()
    a.appendLine(
      "  %-14s size=%-3d slots=%s".format("<trace>", (traceSlots.maxOrNull() ?: -1) + 1, traceSlots))
    return a.toString()
  }

  /**
   * layout-by-type.txt — full composition per type (origins shown, NOT de-duped), each tag annotated
   * with its slot/tier: s<n> = per-span slot, trace<n> = trace-level layer, bkt = bucket.
   */
  private fun layoutByTypeReport(conv: TagConventions, reg: TagRegistry): String {
    val byName = reg.stored.associateBy { it.name }
    val lay = StringBuilder()
    lay.appendLine("# Full tag composition per concrete span type (after extends/include/applies).")
    lay.appendLine("# Not de-duped: a tag from >1 source appears >1 time.")
    lay.appendLine("# annotation: [s<n> per-span slot | trace<n> trace layer | bkt bucketed]  <required>  I=intercepted")
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
          val slot =
            when {
              st == null -> "?"
              st.traceLevel && st.slotted -> "trace${st.slot}"
              st.slotted -> "s${st.slot}"
              else -> "bkt"
            }
          lay.appendLine(
            "    %-26s %-8s %-12s %s".format(t.name, slot, t.required, if (st?.intercepted == true) "I" else ""))
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
    fun tierSlot(st: TagRegistry.StoredTag?): String =
      when {
        st == null -> "?"
        st.traceLevel -> if (st.slotted) "trace${st.slot}" else "trace-bkt"
        st.slotted -> "s${st.slot}"
        else -> "bkt"
      }
    val f = StringBuilder()
    f.appendLine("# Folded tag set per type (extends + include + applies, de-duped), with slots.")
    f.appendLine(
      "# slot: s<n>=per-span bit  trace<n>=trace-level layer bit  bkt=bucketed  trace-bkt=trace-level bucketed  I=intercepted")
    for (type in conv.concreteTypes()) {
      val tags = conv.resolve(type)
      f.appendLine()
      f.appendLine("$type  (${tags.size} tags):")
      for (t in tags) {
        val st = byName[t.name]
        f.appendLine("  %-9s %-26s %s".format(tierSlot(st), t.name, if (st?.intercepted == true) "I" else ""))
      }
    }
    val traceTags =
      reg.stored.filter { it.traceLevel }.sortedWith(compareBy({ !it.slotted }, { it.slot }, { it.name }))
    f.appendLine()
    f.appendLine("<trace>  (${traceTags.size} tags):")
    for (st in traceTags) {
      f.appendLine("  %-9s %-26s %s".format(tierSlot(st), st.name, if (st.intercepted) "I" else ""))
    }
    return f.toString()
  }
}
