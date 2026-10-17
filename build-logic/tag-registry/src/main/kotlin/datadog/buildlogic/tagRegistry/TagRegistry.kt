package datadog.buildlogic.tagRegistry

/**
 * Assigns tag ids from a parsed [TagConventions]. The id encoding mirrors KnownTagCodec: [63-48
 * serial][47-32 slot][31-0 flags].
 *
 * <p>An id is a globally unique serial, a slot, and two classification bits: trace-level and
 * intercepted.
 *
 * <p>The slot comes from GRAPH COLORING the tag co-occurrence graph. Each concrete span type's
 * resolved tags appear together on one span, so they form a clique and need distinct slots; the
 * trace-level tier is its own clique (it lives in a separate TagMap). Tags that never share a clique
 * may share a slot, so the slot count stays bounded by the largest clique, small enough for one
 * occupancy `long`. Every tag resolved onto a span type, or trace-level, is slotted whatever its
 * requirement level; a tag on no modeled span type (the tracer overlay's routing-only keys, tags of
 * unmodeled span types) gets [NO_SLOT]. Slots are for per-span structures that want an exact,
 * compact presence word: the dense tag store (DenseTagMap), and in time a span builder's tag ledger.
 *
 * <p>The intercepted bit marks a tag the tracer may route on the set-path (to a span field or a
 * sampling directive instead of tag storage), as listed by the tracer overlay. A setter called with
 * a constant id then folds the interception test away. TagInterceptor's switch stays the authority
 * on what each tag does; a test there keeps the bit and the switch in agreement.
 *
 * <p>There is one tag, and one id, per Datadog name. A name the conventions declare once per
 * direction, as `peer.port` is, is still one tag: only its OpenTelemetry name depends on the span's
 * direction, so direction is applied when a tag is named, never when it is stored.
 */
class TagRegistry private constructor(val tags: List<Tag>) {
  data class Tag(
    val ddName: String,
    val type: String,
    val required: String,
    val serial: Int,
    val traceLevel: Boolean,
    val intercepted: Boolean,
    /** The tag's slot, or [NO_SLOT] for a tag on no modeled span type. */
    val slot: Int,
    val id: Long,
    /** The tag's OpenTelemetry name in every direction; null when not renamed, or renamed per direction. */
    val otelName: String? = null,
    /** The tag's OpenTelemetry name on spans of each direction that renames it, when [otelName] is null. */
    val scopedOtelNames: Map<TagConventions.Direction, String> = emptyMap(),
  ) {
    /** The tag as reports and generated constant names show it. */
    val name: String
      get() = ddName
  }

  companion object {
    const val FIRST_SERIAL = 1
    const val HAS_SCOPED_ALIAS = 1L shl 0 // low-32 carve bit 0; mirrors KnownTagCodec.HAS_SCOPED_ALIAS
    const val INTERCEPTED = 1L shl 1 // low-32 carve bit 1; mirrors KnownTagCodec.INTERCEPTED
    const val LEVEL_TRACE = 1L shl 2 // low-32 carve bit 2; mirrors KnownTagCodec.LEVEL_TRACE

    const val SLOT_SHIFT = 32 // slot window [47-32]; mirrors KnownTagCodec.SLOT_SHIFT
    const val NO_SLOT = 0xFFFF // all-ones slot: no modeled span type; mirrors KnownTagCodec.NO_SLOT
    const val MAX_SLOTS = 63 // fits one occupancy long; bit 63 is left for unslotted tags to share

    /**
     * Serials stay below 2^15, so bit 63 of an id is never set: every id is positive, clear of 0
     * (unknown) and of KnownTagCodec's negative lookup sentinels.
     */
    const val SERIAL_CAPACITY = 1 shl 15

    /**
     * Mirrors KnownTagCodec's layout -- must stay in sync: serial at [63-48], slot at [47-32],
     * HAS_SCOPED_ALIAS at bit 0 (the tag has an OpenTelemetry name in some directions only),
     * INTERCEPTED at bit 1, LEVEL_TRACE at bit 2, other low bits zero. Every id is made here, and
     * the range check makes each one positive by construction.
     */
    fun encode(
      serial: Int,
      traceLevel: Boolean,
      intercepted: Boolean,
      hasScopedAlias: Boolean = false,
      slot: Int = NO_SLOT,
    ): Long {
      require(slot == NO_SLOT || slot in 0 until MAX_SLOTS) { "slot $slot is outside [0, $MAX_SLOTS)" }
      require(serial in FIRST_SERIAL until SERIAL_CAPACITY) {
        "serial $serial is outside [$FIRST_SERIAL, $SERIAL_CAPACITY): more tags would make tag ids " +
          "negative, where KnownTagCodec's lookup sentinels live"
      }
      var id = (serial.toLong() shl 48) or (slot.toLong() shl SLOT_SHIFT)
      if (traceLevel) id = id or LEVEL_TRACE
      if (intercepted) id = id or INTERCEPTED
      if (hasScopedAlias) id = id or HAS_SCOPED_ALIAS
      return id
    }

    fun build(conv: TagConventions): TagRegistry {
      val traceLevel = conv.traceLevelTags().map { it.ddName }.toSet()
      val renames = conv.otelMappings().groupBy { it.tag.ddName }
      val slots = colorSlots(conv)

      // One tag per Datadog name, in a stable order so serials -- and therefore ids -- are a pure
      // function of the input: intercepted tags first, then by name. Intercepted tags taking the
      // lowest serials keeps TagInterceptor's switch on the serial a dense table of just those tags.
      // A name's declarations per direction merge into its one tag.
      val tags =
        conv.allDeclaredTags().groupBy { it.ddName }
          .toSortedMap(compareBy<String> { it !in conv.interceptedNames }.thenBy { it })
          .values.mapIndexed { i, decls ->
          val first = decls.first()
          require(decls.all { it.type == first.type }) {
            "'${first.ddName}' is declared per direction with different types: ${decls.map { it.type }}"
          }
          val serial = FIRST_SERIAL + i
          val isTraceLevel = first.ddName in traceLevel
          val isIntercepted = first.ddName in conv.interceptedNames
          val (otelName, scoped) = otelNamesOf(first.ddName, renames[first.ddName].orEmpty())
          val slot = slots[first.ddName] ?: NO_SLOT
          Tag(
            first.ddName,
            first.type,
            strongestRequirement(decls.map { it.required }),
            serial,
            isTraceLevel,
            isIntercepted,
            slot,
            id = encode(serial, isTraceLevel, isIntercepted, scoped.isNotEmpty(), slot),
            otelName = otelName,
            scopedOtelNames = scoped,
          )
        }

      validateOtelNames(tags)
      return TagRegistry(tags)
    }

    /**
     * Colors the co-occurrence graph of slotted tags: tags resolved onto one concrete span type, or
     * both trace-level, must get distinct slots. Greedy, most-constrained first (by clique count,
     * then name), so the result is a pure function of the conventions. A name declared per direction
     * is one tag, so it is one vertex.
     */
    private fun colorSlots(conv: TagConventions): Map<String, Int> {
      val cliques = ArrayList<Set<String>>()
      for (type in conv.concreteTypes()) {
        cliques.add(conv.resolve(type).map { it.ddName }.toSet())
      }
      cliques.add(conv.traceLevelTags().map { it.ddName }.toSet())

      val neighbors = HashMap<String, MutableSet<String>>()
      for (clique in cliques) {
        for (a in clique) {
          val adjacent = neighbors.getOrPut(a) { HashSet() }
          for (b in clique) if (a != b) adjacent.add(b)
        }
      }
      val cliqueCount = neighbors.keys.associateWith { tag -> cliques.count { tag in it } }
      val order = neighbors.keys.sortedWith(compareByDescending<String> { cliqueCount.getValue(it) }.thenBy { it })
      val slots = HashMap<String, Int>()
      for (tag in order) {
        val taken = neighbors.getValue(tag).mapNotNull { slots[it] }.toSet()
        var slot = 0
        while (slot in taken) slot++
        require(slot < MAX_SLOTS) {
          "coloring needs more than $MAX_SLOTS slots at '$tag'; one occupancy long holds $MAX_SLOTS"
        }
        slots[tag] = slot
      }
      return slots
    }

    /**
     * A tag's renames, as one name for every direction or one name per direction. The rename of a
     * declaration made per direction applies in that declaration's direction.
     */
    private fun otelNamesOf(
      ddName: String,
      mappings: List<TagConventions.OtelMapping>,
    ): Pair<String?, Map<TagConventions.Direction, String>> {
      val everywhere = mappings.filter { (it.direction ?: it.tag.direction) == null }.map { it.otelName }.distinct()
      require(everywhere.size <= 1) { "'$ddName' has several direction-free OpenTelemetry names: $everywhere" }
      if (everywhere.isNotEmpty()) {
        require(mappings.all { it.otelName == everywhere[0] }) {
          "'$ddName' has a direction-free OpenTelemetry name and a different one per direction"
        }
        return everywhere[0] to emptyMap()
      }
      val scoped = sortedMapOf<TagConventions.Direction, String>()
      for (m in mappings) {
        val direction = m.direction ?: m.tag.direction!!
        val prev = scoped.put(direction, m.otelName)
        require(prev == null || prev == m.otelName) {
          "'$ddName' has two OpenTelemetry names on ${direction.yamlKey} spans: '$prev' and '${m.otelName}'"
        }
      }
      return null to scoped
    }

    private val REQUIREMENT_STRENGTH = listOf("required", "conditional", "recommended", "optional")

    /** The strongest of a name's requirement levels across its declarations per direction. */
    private fun strongestRequirement(levels: List<String>): String = levels.minBy { REQUIREMENT_STRENGTH.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    /**
     * Rejects OpenTelemetry names that collide with any Datadog name. Within each direction,
     * a rename must also belong to a single tag. Direction-free renames reserve their name
     * in every direction. Scoped renames may share a name across directions: `server.address`
     * maps to `http.hostname` inbound and `peer.hostname` outbound.
     */
    private fun validateOtelNames(tags: List<Tag>) {
      val canonical = tags.map { it.ddName }.toSet()
      val owner = HashMap<Pair<TagConventions.Direction, String>, String>()
      for (t in tags) {
        val byDirection =
          t.otelName?.let { otel -> TagConventions.Direction.entries.associateWith { otel } } ?: t.scopedOtelNames
        for ((direction, otel) in byDirection) {
          require(otel !in canonical) {
            "OpenTelemetry name '$otel' (of '${t.name}') collides with canonical tag name '$otel'"
          }
          val prev = owner.put(direction to otel, t.name)
          require(prev == null) {
            "OpenTelemetry name '$otel' is claimed by both '$prev' and '${t.name}' on " +
              "${direction.yamlKey} spans"
          }
        }
      }
    }
  }
}
