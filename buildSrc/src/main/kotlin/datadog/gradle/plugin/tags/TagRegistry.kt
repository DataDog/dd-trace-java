package datadog.gradle.plugin.tags

/**
 * Assigns tag ids from a parsed [TagConventions] plus the Java overlay (intercepted set + reserved
 * registry). The id encoding mirrors KnownTagCodec: [63 intercepted][62-48 serial][47-32 slot][31-0
 * zero] (known ids carry no nameHash — they are dense-store addressed).
 *
 * <p>The `slot` is a single globally stable coordinate from GRAPH COLORING the tag co-occurrence
 * graph. Each concrete span type's resolved tag set (see [TagConventions.resolve]) is a clique — its
 * tags all appear together on one span, so they must get distinct slots. The trace-level tier is its
 * own clique (a separate TagMap on the TraceSegment), so it may reuse slot numbers freely with the
 * span layers. Slots are shared only between tags that never co-occur, so slotCount stays bounded by
 * the largest clique (≤ 64) — small enough that the dense store's presence fast path is a single
 * occupancy `long` (`1L << slot`), which is exactly why the earlier two-tier (group + field bloom)
 * scheme could collapse to one word. Correctness never depends on the coloring (the dense scan is
 * authoritative); only the fast-path hit rate does.
 *
 * <p>Slotting (does a tag get a slot / dense presence bit) is derived from the domain `required`
 * level: required/conditional/recommended tags are colored (slotted), the rest are NO_SLOT
 * (bucketed) and carry no slot bit.
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
    val slot: Int,
    val traceLevel: Boolean,
    val id: Long,
    val otelName: String? = null,
  ) {
    val slotted: Boolean
      get() = slot != NO_SLOT
  }

  data class ReservedTag(
    val name: String,
    val kind: String,
    val field: String?,
    val serial: Int,
    val id: Long,
    val otelName: String? = null,
  )

  /** Java overlay: intercepted tag names + the reserved/special-key registry. */
  class Overlay(val intercepted: Set<String>, val reserved: List<ReservedDef>) {
    data class ReservedDef(
      val name: String,
      val kind: String,
      val field: String?,
      val otelName: String? = null,
    )

    companion object {
      @Suppress("UNCHECKED_CAST")
      fun parse(root: Map<String, Any?>): Overlay {
        val intercepted = (root["intercepted"] as? List<String>)?.toSet() ?: emptySet()
        val reserved =
          (root["reserved"] as? List<Map<String, Any?>>)?.map { m ->
            ReservedDef(
              m["dd-name"].toString(),
              (m["kind"] as? String) ?: "directive",
              m["field"] as? String,
              // Reserved tags are not required to declare otel-name; absent or "none" -> no name.
              (m["otel-name"] as? String)?.takeUnless { it == "none" })
          } ?: emptyList()
        return Overlay(intercepted, reserved)
      }
    }
  }

  companion object {
    const val FIRST_STORED_SERIAL = 256
    const val NO_SLOT = 0xFFFF // slot all-ones sentinel (16 bits); mirrors KnownTagCodec.NO_SLOT
    const val MAX_SLOT = 63 // one occupancy long: colored slots must fit in [0, 63]
    const val LEVEL_TRACE = 1L shl 2 // low-32 carve bit 2; mirrors KnownTagCodec.LEVEL_TRACE
    const val TRACE_LAYER = "<trace>"

    // Domain `required` levels that get a colored slot (the rest are bucketed with NO_SLOT).
    val COLORABLE = setOf("required", "conditional", "recommended")

    /**
     * Mirrors KnownTagCodec.makeTagId(serial, slot) + intercepted()/traceLevel() — must stay in
     * sync. slot [47-32], LEVEL_TRACE at bit 2, other low bits zero.
     */
    fun encode(serial: Int, intercepted: Boolean, slot: Int, traceLevel: Boolean): Long {
      var id = (serial.toLong() shl 48) or ((slot.toLong() and 0xFFFF) shl 32)
      if (traceLevel) id = id or LEVEL_TRACE
      if (intercepted) id = id or Long.MIN_VALUE
      return id
    }

    fun build(conv: TagConventions, overlay: Overlay): TagRegistry {
      val all = conv.allStoredTags()
      val traceNames = conv.traceLevelTags().map { it.name }.toSet()
      val colorable = all.filter { it.required in COLORABLE }.map { it.name }.toSet()

      // Co-occurrence cliques: each concrete type's resolved colorable tags, plus the trace-level
      // tier as its own clique (a separate TagMap -> free to reuse span slot numbers). Tags in the
      // same clique must get distinct colors; tags never sharing a clique may share a color.
      val cliques = ArrayList<Set<String>>()
      for (type in conv.concreteTypes()) {
        cliques.add(conv.resolve(type).map { it.name }.filter { it in colorable }.toSet())
      }
      cliques.add(traceNames.filter { it in colorable }.toSet())

      // Adjacency: an edge between every pair of tags that co-occur in some clique.
      val adj = HashMap<String, MutableSet<String>>()
      colorable.forEach { adj[it] = HashSet() }
      for (clique in cliques) {
        val members = clique.toList()
        for (i in members.indices) for (j in i + 1 until members.size) {
          adj.getValue(members[i]).add(members[j])
          adj.getValue(members[j]).add(members[i])
        }
      }

      // Greedy coloring, most-constrained-first (by clique membership count, then name for a stable
      // tie-break). Each tag takes the smallest color not used by an already-colored neighbor.
      val cliqueCount = colorable.associateWith { n -> cliques.count { n in it } }
      val order = colorable.sortedWith(compareByDescending<String> { cliqueCount.getValue(it) }.thenBy { it })
      val color = HashMap<String, Int>()
      for (n in order) {
        val used = adj.getValue(n).mapNotNull { color[it] }.toSet()
        var c = 0
        while (c in used) c++
        color[n] = c
      }
      val slotCount = (color.values.maxOrNull() ?: -1) + 1
      require(slotCount <= MAX_SLOT + 1) {
        "coloring produced $slotCount slots; the single occupancy long holds at most ${MAX_SLOT + 1}"
      }

      val reserved =
        overlay.reserved.mapIndexed { i, v ->
          val serial = 1 + i
          ReservedTag(
            v.name, v.kind, v.field, serial,
            encode(serial, intercepted = true, slot = NO_SLOT, traceLevel = false),
            v.otelName)
        }

      // Stored tags in a stable order (by name); serials are a dense global counter from
      // FIRST_STORED_SERIAL. slot comes from the coloring (NO_SLOT for non-colorable/bucketed tags).
      val stored =
        all.sortedBy { it.name }.mapIndexed { i, t ->
          val serial = FIRST_STORED_SERIAL + i
          val intercepted = t.name in overlay.intercepted
          val slot = color[t.name] ?: NO_SLOT
          val traceLevel = t.name in traceNames
          StoredTag(
            t.name, t.type, t.required, serial, intercepted, slot, traceLevel,
            id = encode(serial, intercepted, slot, traceLevel),
            otelName = t.otelName)
        }

      validateOtelNames(stored, reserved)
      return TagRegistry(stored, reserved, slotCount)
    }

    /**
     * An OpenTelemetry name must be unambiguous: it may not collide with a DIFFERENT tag's canonical
     * name, nor be claimed by two different tags. A tag sharing its OWN Datadog name across both
     * namespaces (the same-name tri-state, e.g. http.route) is allowed — keyOf still has one answer.
     * Fail the build loudly rather than silently pick a winner.
     */
    private fun validateOtelNames(stored: List<StoredTag>, reserved: List<ReservedTag>) {
      val canonical = (stored.map { it.name } + reserved.map { it.name }).toSet()
      val owner = HashMap<String, String>()
      val check = { name: String, otel: String? ->
        if (otel != null) {
          require(otel == name || otel !in canonical) {
            "OpenTelemetry name '$otel' (of '$name') collides with a different canonical tag name"
          }
          val prev = owner.put(otel, name)
          require(prev == null) { "OpenTelemetry name '$otel' is claimed by both '$prev' and '$name'" }
        }
      }
      stored.forEach { check(it.name, it.otelName) }
      reserved.forEach { check(it.name, it.otelName) }
    }
  }
}
