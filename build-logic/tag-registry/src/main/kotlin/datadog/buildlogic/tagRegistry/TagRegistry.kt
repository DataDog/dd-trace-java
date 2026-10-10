package datadog.buildlogic.tagRegistry

/**
 * Assigns tag ids from a parsed [TagConventions]. The id encoding mirrors KnownTagCodec: [63-48
 * serial][47-32 slot][31-0 flags].
 *
 * <p>An id is a globally unique serial, a dense-store slot, and two classification bits:
 * trace-level and intercepted.
 *
 * <p>The slot comes from GRAPH COLORING the tag co-occurrence graph. Each concrete span type's
 * resolved tags appear together on one span, so they form a clique and need distinct slots; the
 * trace-level tier is its own clique (it lives in a separate TagMap). Tags that never share a clique
 * may share a slot -- a tag declared per direction (peer.port inbound and outbound) typically does --
 * so the slot count stays bounded by the largest clique, small enough for one occupancy `long` in
 * the dense store. Every tag resolved onto a span type, or trace-level, is slotted whatever its
 * requirement level; a tag on no modeled span type (the tracer overlay's routing-only keys, tags of
 * unmodeled span types) gets [NO_SLOT], and the dense store gives those one shared presence bit.
 *
 * <p>The intercepted bit marks a tag the tracer may route on the set-path (to a span field or a
 * sampling directive instead of tag storage), as listed by the tracer overlay. A setter called with
 * a constant id then folds the interception test away. TagInterceptor's switch stays the authority
 * on what each tag does; a test there keeps the bit and the switch in agreement.
 */
class TagRegistry private constructor(val tags: List<Tag>) {
  data class Tag(
    val identity: TagConventions.TagIdentity,
    val type: String,
    val required: String,
    val serial: Int,
    val traceLevel: Boolean,
    val intercepted: Boolean,
    /** The dense-store slot, or [NO_SLOT] when the tag is stored by hash. */
    val slot: Int,
    val id: Long,
    /** The tag's OpenTelemetry name, in [otelDirection] or every direction; null when not renamed. */
    val declaredOtelName: String? = null,
    /**
     * The one direction [declaredOtelName] applies in, or null when it applies in every direction. A
     * tag has one declaration, so its rename covers either every direction or exactly one.
     */
    val otelDirection: TagConventions.Direction? = null,
  ) {
    /** The identity's label, as reports and constant names show it. */
    val name: String = identity.label
    val ddName: String = identity.ddName
    val sharedNameDirection: TagConventions.Direction? = identity.direction

    /**
     * The rename included in the direction-free lookup tables, or null when absent or scoped
     * to one direction. Scoped renames remain in `declaredOtelName` for later resolution.
     */
    val otelName: String? = declaredOtelName.takeIf { otelDirection == null }
  }

  companion object {
    const val FIRST_SERIAL = 1
    const val LEVEL_TRACE = 1L shl 2 // low-32 carve bit 2; mirrors KnownTagCodec.LEVEL_TRACE
    const val INTERCEPTED = 1L shl 1 // low-32 carve bit 1; mirrors KnownTagCodec.INTERCEPTED
    const val SHARED_NAME = 1L shl 0 // low-32 carve bit 0; mirrors KnownTagCodec.SHARED_NAME

    const val SLOT_SHIFT = 32 // slot window [47-32]; mirrors KnownTagCodec.SLOT_SHIFT
    const val NO_SLOT = 0xFFFF // all-ones slot: stored by hash; mirrors KnownTagCodec.NO_SLOT
    const val MAX_SLOTS = 63 // one occupancy long in the dense store; bit 63 is shared by unslotted tags


    /**
     * Serials stay below 2^15, so bit 63 of an id is never set: every id is positive, clear of 0
     * (unknown) and of KnownTagCodec's negative lookup sentinels.
     */
    const val SERIAL_CAPACITY = 1 shl 15

    /**
     * Mirrors KnownTagCodec's layout -- must stay in sync: serial at [63-48], slot at [47-32],
     * LEVEL_TRACE at bit 2, INTERCEPTED at bit 1, SHARED_NAME at bit 0, other low bits zero. Every id
     * is made here, and the range check makes each one positive by construction.
     */
    fun encode(
      serial: Int,
      traceLevel: Boolean,
      intercepted: Boolean,
      sharedName: Boolean = false,
      slot: Int,
    ): Long {
      require(slot == NO_SLOT || slot in 0 until MAX_SLOTS) { "slot $slot is outside [0, $MAX_SLOTS)" }
      require(serial in FIRST_SERIAL until SERIAL_CAPACITY) {
        "serial $serial is outside [$FIRST_SERIAL, $SERIAL_CAPACITY): more tags would make tag ids " +
          "negative, where KnownTagCodec's lookup sentinels live"
      }
      var id = (serial.toLong() shl 48) or (slot.toLong() shl SLOT_SHIFT)
      if (traceLevel) id = id or LEVEL_TRACE
      if (intercepted) id = id or INTERCEPTED
      if (sharedName) id = id or SHARED_NAME
      return id
    }

    fun build(conv: TagConventions): TagRegistry {
      val traceLevel = conv.traceLevelTags().map { it.identity }.toSet()
      val renames = conv.otelMappings().associateBy { it.tag }

      val slots = colorSlots(conv)

      // Stable order (by name) so serials -- and therefore ids -- are a pure function of the input.
      val tags =
        conv.allDeclaredTags().sortedBy { it.name }.mapIndexed { i, t ->
          val serial = FIRST_SERIAL + i
          val isTraceLevel = t.identity in traceLevel
          val isIntercepted = t.ddName in conv.interceptedNames
          Tag(
            t.identity,
            t.type,
            t.required,
            serial,
            isTraceLevel,
            isIntercepted,
            slot = slots[t.identity] ?: NO_SLOT,
            id = encode(serial, isTraceLevel, isIntercepted, t.identity.direction != null, slots[t.identity] ?: NO_SLOT),
            declaredOtelName = renames[t.identity]?.otelName,
            otelDirection = renames[t.identity]?.direction,
          )
        }

      validateOtelNames(tags)
      return TagRegistry(tags)
    }

    /**
     * Colors the co-occurrence graph of slotted tags: tags resolved onto one concrete span type, or
     * both trace-level, must get distinct slots. Greedy, most-constrained first (by clique count,
     * then label), so the result is a pure function of the conventions.
     */
    private fun colorSlots(conv: TagConventions): Map<TagConventions.TagIdentity, Int> {
      val cliques = ArrayList<Set<TagConventions.TagIdentity>>()
      for (type in conv.concreteTypes()) {
        cliques.add(conv.resolve(type).map { it.identity }.toSet())
      }
      cliques.add(conv.traceLevelTags().map { it.identity }.toSet())

      val neighbors = HashMap<TagConventions.TagIdentity, MutableSet<TagConventions.TagIdentity>>()
      for (clique in cliques) {
        for (a in clique) {
          val adjacent = neighbors.getOrPut(a) { HashSet() }
          for (b in clique) if (a != b) adjacent.add(b)
        }
      }
      val cliqueCount = neighbors.keys.associateWith { tag -> cliques.count { tag in it } }
      val order =
        neighbors.keys.sortedWith(
          compareByDescending<TagConventions.TagIdentity> { cliqueCount.getValue(it) }.thenBy { it.label }
        )
      val slots = HashMap<TagConventions.TagIdentity, Int>()
      for (tag in order) {
        val taken = neighbors.getValue(tag).mapNotNull { slots[it] }.toSet()
        var slot = 0
        while (slot in taken) slot++
        require(slot < MAX_SLOTS) {
          "coloring needs more than $MAX_SLOTS slots at '${tag.label}'; the dense store's occupancy " +
            "long holds $MAX_SLOTS"
        }
        slots[tag] = slot
      }
      return slots
    }

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
        val otel = t.declaredOtelName ?: continue
        for (direction in t.otelDirection?.let { listOf(it) } ?: TagConventions.Direction.entries) {
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
