package datadog.gradle.plugin.tags

/**
 * Assigns tag ids + slots from a parsed [TagConventions] plus the Java overlay (intercepted set +
 * virtual registry). Slots come from deterministic greedy graph coloring over the co-occurrence
 * graph; colorability is derived from the domain `required` level. The id encoding mirrors
 * KnownTagCodec: [63 intercepted][62-48 serial][47-32 slot][31-0 zero] (known ids carry no nameHash).
 *
 * Level-split: trace/process-constant tags (`source: core`) are their own TagMap "type" -- the
 * `<trace>` layer that lives on the TraceSegment, not the per-span layout. It is just another
 * coloring layer, so its tags get slots among themselves and reuse slot numbers with the span
 * layers (a different TagMap, so no collision).
 */
class TagRegistry
private constructor(
  val stored: List<StoredTag>,
  val virtuals: List<VirtualTag>,
  val slotCount: Int,
) {
  data class StoredTag(
    val name: String,
    val type: String,
    val required: String,
    val serial: Int,
    val intercepted: Boolean,
    val slot: Int,
    val traceLevel: Boolean,
    val id: Long,
  ) {
    val slotted: Boolean
      get() = slot != NO_SLOT
  }

  data class VirtualTag(
    val name: String,
    val kind: String,
    val field: String?,
    val serial: Int,
    val id: Long,
  )

  /** Java overlay: intercepted tag names + the virtual/special-key registry. */
  class Overlay(val intercepted: Set<String>, val virtuals: List<VirtualDef>) {
    data class VirtualDef(val name: String, val kind: String, val field: String?)

    companion object {
      @Suppress("UNCHECKED_CAST")
      fun parse(root: Map<String, Any?>): Overlay {
        val intercepted = (root["intercepted"] as? List<String>)?.toSet() ?: emptySet()
        val virtuals =
          (root["virtual"] as? List<Map<String, Any?>>)?.map { m ->
            VirtualDef(m["tag"].toString(), (m["kind"] as? String) ?: "directive", m["field"] as? String)
          } ?: emptyList()
        return Overlay(intercepted, virtuals)
      }
    }
  }

  companion object {
    const val FIRST_STORED_SERIAL = 256
    const val NO_SLOT = 0xFFFF
    const val TRACE_LAYER = "<trace>"
    val COLORABLE = setOf("required", "conditional", "recommended")

    /** Mirrors KnownTagCodec.tagId(serial, intercepted, slot) — must stay in sync with it. */
    fun encode(serial: Int, intercepted: Boolean, slot: Int): Long {
      val id = (serial.toLong() shl 48) or ((slot.toLong() and 0xFFFF) shl 32)
      return if (intercepted) id or Long.MIN_VALUE else id
    }

    fun build(conv: TagConventions, overlay: Overlay): TagRegistry {
      val all = conv.allStoredTags()
      val coOcc = conv.coOccurrence() // span type -> {tag names}

      val colorable = all.filter { it.required in COLORABLE }.map { it.name }.toSet()
      // Trace-level tier is EXPLICIT (the trace_level section), not inferred from source — so
      // core-set-but-per-span tags (parent_id / integration / svc_src on `base`) stay per-span.
      val traceNames = conv.traceLevelTags().map { it.name }.toSet()

      // Coloring layers = each concrete span type + the <trace> layer (colorable trace-level tags).
      // Tags within a layer form a clique.
      val layers = LinkedHashMap<String, Set<String>>()
      for ((type, names) in coOcc) layers[type] = names.filter { it in colorable && it !in traceNames }.toSet()
      layers[TRACE_LAYER] = traceNames.filter { it in colorable }.toSet()

      val adj = HashMap<String, MutableSet<String>>()
      colorable.forEach { adj[it] = HashSet() }
      for ((_, members) in layers) {
        val clique = members.toList()
        for (i in clique.indices) for (j in i + 1 until clique.size) {
          adj[clique[i]]!!.add(clique[j])
          adj[clique[j]]!!.add(clique[i])
        }
      }

      // Color the tags in the most layers first, so always-present base tags get the low slots.
      val freq = colorable.associateWith { n -> layers.count { n in it.value } }
      val order = colorable.sortedWith(compareByDescending<String> { freq[it] }.thenBy { it })
      val color = HashMap<String, Int>()
      for (n in order) {
        val used = adj[n]!!.mapNotNull { color[it] }.toSet()
        var c = 0
        while (c in used) c++
        color[n] = c
      }
      val slotCount = (color.values.maxOrNull() ?: -1) + 1

      val virtuals =
        overlay.virtuals.mapIndexed { i, v ->
          val serial = 1 + i
          VirtualTag(v.name, v.kind, v.field, serial, encode(serial, intercepted = true, slot = NO_SLOT))
        }
      val stored =
        all.sortedBy { it.name }.mapIndexed { i, t ->
          val serial = FIRST_STORED_SERIAL + i
          val intercepted = t.name in overlay.intercepted
          val slot = color[t.name] ?: NO_SLOT
          StoredTag(t.name, t.type, t.required, serial, intercepted, slot,
            traceLevel = t.name in traceNames, id = encode(serial, intercepted, slot))
        }

      return TagRegistry(stored, virtuals, slotCount)
    }
  }
}
