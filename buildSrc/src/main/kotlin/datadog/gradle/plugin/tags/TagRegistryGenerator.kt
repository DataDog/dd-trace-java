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

  /** tag-assignment.txt — serials, group/field-decls, ids, per-type field-decl sets (assignment check). */
  private fun assignmentReport(conv: TagConventions, reg: TagRegistry): String {
    val byName = reg.stored.associateBy { it.name }
    val a = StringBuilder()
    a.appendLine(
      "# Tag id assignment.  slotCount=${reg.slotCount}  stored=${reg.stored.size}  reserved=${reg.reserved.size}")
    a.appendLine()
    a.appendLine("# STORED   serial group field int id                 required     name")
    for (t in reg.stored) {
      a.appendLine(
        "  %6d %5d %5s  %s  %-18s %-12s %s".format(
          t.serial,
          t.groupDecl,
          if (t.slotted) t.fieldDecl.toString() else "-",
          if (t.intercepted) "I" else "-",
          "0x%016X".format(t.id),
          t.required,
          t.name))
    }
    a.appendLine()
    a.appendLine("# RESERVED serial       id                 kind         name")
    for (v in reg.reserved) {
      a.appendLine(
        "  %6d      %-18s %-12s %s%s".format(
          v.serial, "0x%016X".format(v.id), v.kind, v.name, v.field?.let { " -> $it" } ?: ""))
    }
    a.appendLine()
    a.appendLine("# PER-TYPE slotted coordinates as group:field. field-decl restarts per group, so the")
    a.appendLine("# group-decl tier disambiguates the shared field-bit word.  <trace> = trace-level layer.")
    for (type in conv.concreteTypes()) {
      val coords =
        conv.resolve(type).mapNotNull { byName[it.name] }
          .filter { it.slotted && !it.traceLevel }
          .map { it.groupDecl to it.fieldDecl }
          .sortedWith(compareBy({ it.first }, { it.second }))
          .map { "${it.first}:${it.second}" }
      a.appendLine("  %-14s count=%-3d coords=%s".format(type, coords.size, coords))
    }
    val traceCoords =
      reg.stored.filter { it.traceLevel && it.slotted }
        .map { it.groupDecl to it.fieldDecl }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { "${it.first}:${it.second}" }
    a.appendLine("  %-14s count=%-3d coords=%s".format("<trace>", traceCoords.size, traceCoords))
    return a.toString()
  }

  /**
   * layout-by-type.txt — full composition per type (origins shown, NOT de-duped), each tag annotated
   * with its field-decl/tier: f<n> = dense field-decl, trace<n> = trace-level layer, bkt = bucket.
   */
  private fun layoutByTypeReport(conv: TagConventions, reg: TagRegistry): String {
    val byName = reg.stored.associateBy { it.name }
    val lay = StringBuilder()
    lay.appendLine("# Full tag composition per concrete span type (after extends/include/applies).")
    lay.appendLine("# Not de-duped: a tag from >1 source appears >1 time.")
    lay.appendLine("# annotation: [g<grp>f<n> dense group:field-decl | trace g<grp>f<n> trace layer | bkt bucketed]  <required>  I=intercepted")
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
              st.traceLevel && st.slotted -> "trace g${st.groupDecl}f${st.fieldDecl}"
              st.slotted -> "g${st.groupDecl}f${st.fieldDecl}"
              else -> "bkt"
            }
          lay.appendLine(
            "    %-26s %-12s %-12s %s".format(t.name, field, t.required, if (st?.intercepted == true) "I" else ""))
        }
      }
    }
    return lay.toString()
  }

  /**
   * folded-types.txt — each type's full resolved set (extends + include + applies, DE-DUPED) with its
   * field-decl; plus the <trace> type. This is the "type with everything folded in" view.
   */
  private fun foldedTypesReport(conv: TagConventions, reg: TagRegistry): String {
    val byName = reg.stored.associateBy { it.name }
    fun tierField(st: TagRegistry.StoredTag?): String =
      when {
        st == null -> "?"
        st.traceLevel -> if (st.slotted) "trace g${st.groupDecl}f${st.fieldDecl}" else "trace-bkt"
        st.slotted -> "g${st.groupDecl}f${st.fieldDecl}"
        else -> "bkt"
      }
    val f = StringBuilder()
    f.appendLine("# Folded tag set per type (extends + include + applies, de-duped), with group:field-decls.")
    f.appendLine(
      "# field: g<grp>f<n>=dense group:field-decl  trace ...=trace-level layer  bkt=bucketed  trace-bkt=trace-level bucketed  I=intercepted")
    for (type in conv.concreteTypes()) {
      val tags = conv.resolve(type)
      f.appendLine()
      f.appendLine("$type  (${tags.size} tags):")
      for (t in tags) {
        val st = byName[t.name]
        f.appendLine("  %-12s %-26s %s".format(tierField(st), t.name, if (st?.intercepted == true) "I" else ""))
      }
    }
    val traceTags =
      reg.stored.filter { it.traceLevel }.sortedWith(compareBy({ !it.slotted }, { it.fieldDecl }, { it.name }))
    f.appendLine()
    f.appendLine("<trace>  (${traceTags.size} tags):")
    for (st in traceTags) {
      f.appendLine("  %-12s %-26s %s".format(tierField(st), st.name, if (st.intercepted) "I" else ""))
    }
    return f.toString()
  }
}
