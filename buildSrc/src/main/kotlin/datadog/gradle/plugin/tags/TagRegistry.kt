package datadog.gradle.plugin.tags

/**
 * Assigns tag ids from a parsed [TagConventions] plus the Java overlay (intercepted set + reserved
 * registry). The id encoding mirrors KnownTagCodec: [63 intercepted][62-48 serial][47-42
 * group-decl][41-32 field-decl][31-0 zero] (known ids carry no nameHash — they are dense-store
 * addressed).
 *
 * <p>Groups are the declaration sources: the trace-level tier, each span type, and each mixin each
 * get their own `group-decl` (see [TagConventions.declarationGroups]). A tag's home group is where
 * it is *declared* (first declaration wins across the group order); the same tag reached via
 * extends/include/applies is not given a second id. Within each group, `field-decl` is a plain
 * ordinal over that group's slotted tags — it restarts at 0 per group, which is exactly what makes
 * the two-tier presence fast path pay off: the shared field-bit word would collide across groups,
 * but the group-decl tier disambiguates. No graph coloring; correctness never depends on the
 * coordinate→bit collision rate (the dense scan is authoritative).
 *
 * <p>Slotting (does a tag get a field-decl / dense slot) is derived from the domain `required`
 * level: required/conditional/recommended tags are slotted (dense), the rest are NO_SLOT
 * (bucketed) and carry only their home group-decl (cosmetic — they never touch the dense masks).
 */
class TagRegistry
private constructor(
  val stored: List<StoredTag>,
  val reserved: List<ReservedTag>,
  val slotCount: Int,
) {
  data class StoredTag(
    val name: String,
    val type: String,
    val required: String,
    val serial: Int,
    val intercepted: Boolean,
    val groupDecl: Int,
    val fieldDecl: Int,
    val traceLevel: Boolean,
    val id: Long,
  ) {
    val slotted: Boolean
      get() = fieldDecl != NO_SLOT
  }

  data class ReservedTag(
    val name: String,
    val kind: String,
    val field: String?,
    val serial: Int,
    val id: Long,
  )

  /** Java overlay: intercepted tag names + the reserved/special-key registry. */
  class Overlay(val intercepted: Set<String>, val reserved: List<ReservedDef>) {
    data class ReservedDef(val name: String, val kind: String, val field: String?)

    companion object {
      @Suppress("UNCHECKED_CAST")
      fun parse(root: Map<String, Any?>): Overlay {
        val intercepted = (root["intercepted"] as? List<String>)?.toSet() ?: emptySet()
        val reserved =
          (root["reserved"] as? List<Map<String, Any?>>)?.map { m ->
            ReservedDef(m["tag"].toString(), (m["kind"] as? String) ?: "directive", m["field"] as? String)
          } ?: emptyList()
        return Overlay(intercepted, reserved)
      }
    }
  }

  companion object {
    const val FIRST_STORED_SERIAL = 256
    const val NO_SLOT = 0x3FF // field-decl all-ones sentinel (10 bits); mirrors KnownTagCodec.NO_SLOT
    const val GROUP_DECL_MAX = 0x3F // group-decl is 6 bits [47-42]; mirrors KnownTagCodec.GROUP_DECL_MASK
    const val TRACE_LAYER = "<trace>"

    // Domain `required` levels that get a dense field-decl (the rest are bucketed with NO_SLOT).
    val SLOTTED = setOf("required", "conditional", "recommended")

    /**
     * Mirrors KnownTagCodec.tagId(serial, groupDecl, fieldDecl) + intercepted() — must stay in sync
     * with it. group-decl [47-42], field-decl [41-32], low 32 bits zero.
     */
    fun encode(serial: Int, intercepted: Boolean, groupDecl: Int, fieldDecl: Int): Long {
      val id =
        (serial.toLong() shl 48) or
          ((groupDecl.toLong() and 0x3F) shl 42) or
          ((fieldDecl.toLong() and 0x3FF) shl 32)
      return if (intercepted) id or Long.MIN_VALUE else id
    }

    fun build(conv: TagConventions, overlay: Overlay): TagRegistry {
      val traceNames = conv.traceLevelTags().map { it.name }.toSet()

      val reserved =
        overlay.reserved.mapIndexed { i, v ->
          val serial = 1 + i
          ReservedTag(
            v.name, v.kind, v.field, serial,
            encode(serial, intercepted = true, groupDecl = 0, fieldDecl = NO_SLOT))
        }

      // Walk the declaration groups in order; each group gets the next group-decl. Within a group,
      // field-decl restarts at 0 and counts only slotted tags (non-slotted -> NO_SLOT). A tag is
      // assigned at its first declaration; the same name seen again in a later group is skipped.
      val stored = ArrayList<StoredTag>()
      val assigned = HashSet<String>()
      var nextSerial = FIRST_STORED_SERIAL
      var maxGroupSlots = 0
      conv.declarationGroups().forEachIndexed { groupDecl, group ->
        require(groupDecl <= GROUP_DECL_MAX) {
          "too many declaration groups (${groupDecl + 1}); group-decl is 6 bits (max ${GROUP_DECL_MAX + 1})"
        }
        var nextFieldDecl = 0
        for (t in group.tags) {
          if (!assigned.add(t.name)) continue // first declaration wins
          val serial = nextSerial++
          val intercepted = t.name in overlay.intercepted
          val fieldDecl = if (t.required in SLOTTED) nextFieldDecl++ else NO_SLOT
          stored.add(
            StoredTag(
              t.name, t.type, t.required, serial, intercepted,
              groupDecl = groupDecl, fieldDecl = fieldDecl,
              traceLevel = t.name in traceNames,
              id = encode(serial, intercepted, groupDecl, fieldDecl)))
        }
        if (nextFieldDecl > maxGroupSlots) maxGroupSlots = nextFieldDecl
      }

      // slotCount = (max stored field-decl) + 1. With per-group numbering that is the largest
      // slotted-tag count in any single group.
      return TagRegistry(stored, reserved, slotCount = maxGroupSlots)
    }
  }
}
