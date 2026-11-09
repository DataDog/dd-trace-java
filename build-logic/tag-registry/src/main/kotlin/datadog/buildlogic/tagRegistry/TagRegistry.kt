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
 * occupancy `long`. A span type with more tags than slots fits as long as its tags at a
 * `required`, `conditional` or `recommended` level do: on overflow, its `optional` and `opt_in` tags
 * share slots with each other. Every tag resolved onto a span type, or trace-level, is slotted; a
 * tag on no modeled span type (the tracer overlay's routing-only keys, tags of unmodeled span types)
 * gets [NO_SLOT]. Nothing in the tracer reads slots yet: they are for
 * per-span structures that want an exact, compact presence word, such as a dense tag store or a
 * span builder's tag ledger.
 *
 * <p>The intercepted bit marks a tag the tracer may route on the set-path (to a span field or a
 * sampling directive instead of tag storage), as listed by the tracer overlay. A setter called with
 * a constant id then folds the interception test away. TagInterceptor's switch stays the authority
 * on what each tag does; a test there keeps the bit and the switch in agreement.
 *
 * <p>There is one tag, and one id, per meaning, and an id's names never depend on the span: id to
 * name is always a table lookup. A Datadog name shared by several meanings -- peer.port is the remote
 * server's port on client spans and the client's port on server spans -- belongs to several concepts
 * (declared in `concepts:`), and only resolving a name to an id needs the span's kind. Concepts'
 * names are left out of the direction-free name lookup.
 */
class TagRegistry private constructor(
  val tags: List<Tag>,
  /** KnownTags constant bases that replace a sanitized Datadog or OpenTelemetry name. */
  val javaNames: Map<String, String> = emptyMap(),
) {
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
    /** The tag's slot is shared with another optional tag on one of its span types, after an overflow. */
    val sharesSlot: Boolean = false,
    /** A concept from `concepts:`, whose names are shared with other ids; see [resolvesOn]. */
    val concept: Boolean = false,
    /** The Datadog name this id is emitted under: its own name, unless a concept says otherwise. */
    val datadogName: String = ddName,
    /** For a concept, the span kinds on which its names resolve to it. */
    val resolvesOn: Set<TagConventions.SpanKind> = emptySet(),
  ) {

    /** The tag as reports and generated constant names show it. */
    val name: String
      get() = ddName
  }

  companion object {
    const val FIRST_SERIAL = 1
    const val INTERCEPTED = 1L shl 1 // low-32 carve bit 1; mirrors KnownTagCodec.INTERCEPTED
    const val LEVEL_TRACE = 1L shl 2 // low-32 carve bit 2; mirrors KnownTagCodec.LEVEL_TRACE

    const val SLOT_SHIFT = 32 // slot window [47-32]; mirrors KnownTagCodec.SLOT_SHIFT
    const val NO_SLOT = 0xFFFF // all-ones slot: no modeled span type; mirrors KnownTagCodec.NO_SLOT
    const val MAX_SLOTS = 63 // fits one occupancy long; bit 63 is left for unslotted tags to share

    /**
     * Mirrors KnownTagCodec's layout -- must stay in sync: serial at [63-48], slot at [47-32],
     * INTERCEPTED at bit 1, LEVEL_TRACE at bit 2, other low bits zero.
     */
    fun encode(serial: Int, traceLevel: Boolean, intercepted: Boolean, slot: Int = NO_SLOT): Long {
      require(slot == NO_SLOT || slot in 0 until MAX_SLOTS) { "slot $slot is outside [0, $MAX_SLOTS)" }
      var id = (serial.toLong() shl 48) or (slot.toLong() shl SLOT_SHIFT)
      if (traceLevel) id = id or LEVEL_TRACE
      if (intercepted) id = id or INTERCEPTED
      return id
    }

    fun build(conv: TagConventions): TagRegistry {
      val traceLevel = conv.traceLevelTags().map { it.ddName }.toSet()
      val renames = conv.otelMappings().groupBy { it.tag.ddName }
      val (slots, sharing) = colorSlots(conv)

      // One tag per Datadog name, in a stable order (by name) so serials -- and therefore ids -- are
      // a pure function of the input. A name's declarations per direction merge into its one tag.
      val tags =
        conv.allDeclaredTags().groupBy { it.ddName }.toSortedMap().values.mapIndexed { i, decls ->
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
            id = encode(serial, isTraceLevel, isIntercepted, slot),
            otelName = otelName,
            scopedOtelNames = scoped,
            sharesSlot = first.ddName in sharing,
            concept = first.concept,
            datadogName = first.datadogName ?: first.ddName,
            resolvesOn = first.resolvesOn,
          )
        }

      validateOtelNames(tags)
      validateProjections(tags)
      val names = tags.flatMap { listOfNotNull(it.ddName, it.otelName) + it.scopedOtelNames.values }.toSet()
      val unknownJavaNames = conv.javaNames.keys - names
      require(unknownJavaNames.isEmpty()) {
        "java_names names no registered tag or OpenTelemetry name: $unknownJavaNames"
      }
      return TagRegistry(claimSharedNames(tags), conv.javaNames)
    }

    /**
     * A Datadog name that is also another tag's direction-scoped OpenTelemetry name names different
     * things on different spans (before concepts, `server.port` was the server's own port, and
     * peer.port's OpenTelemetry name on outbound spans). That is ambiguity the conventions must
     * declare as concepts, not something the generator resolves on its own.
     */
    private fun claimSharedNames(tags: List<Tag>): List<Tag> {
      val claimedBy = HashMap<String, MutableSet<TagConventions.Direction>>()
      for (t in tags) {
        if (t.concept) continue
        for ((direction, otel) in t.scopedOtelNames) {
          claimedBy.getOrPut(otel) { mutableSetOf() }.add(direction)
        }
      }
      for (t in tags) {
        if (t.concept) continue
        val claimed = claimedBy[t.ddName] ?: continue
        throw IllegalArgumentException(
          "'${t.name}' is a Datadog name, and also another tag's OpenTelemetry name on " +
            "${claimed.joinToString { it.yamlKey }} spans, so it names different things on different " +
            "spans. Declare each meaning once as a concept in the top-level `concepts:` section, with " +
            "'${t.name}' as its Datadog name where it applies."
        )
      }
      return tags
    }

    /** Requirement levels whose tags may share a slot with each other when a span type overflows. */
    private val OCCASIONAL = setOf("optional", "opt_in")

    /**
     * Colors the co-occurrence graph of slotted tags: tags resolved onto one concrete span type, or
     * both trace-level, get distinct slots while the slots last. Tags that are `required`,
     * `conditional` or `recommended` on some span type are colored first, most-constrained first (by
     * clique count, then name), so they take the low slots and always get exact ones. Only on a span
     * type with more tags than slots may two tags that are both `optional` or `opt_in` there share a
     * slot, and only once the free slots run out, on the slot the fewest of them already hold; a span
     * type that fits never has two tags on one slot. Returns the slots, and
     * the tags that share one. The result is a pure function of the conventions.
     */
    private fun colorSlots(conv: TagConventions): Pair<Map<String, Int>, Set<String>> {
      val cliques = ArrayList<List<TagConventions.Tag>>()
      for (type in conv.concreteTypes()) cliques.add(conv.resolve(type))
      cliques.add(conv.traceLevelTags())

      // Hard edges keep two tags apart; soft edges, between two occasional tags on a span type that
      // overflows, are kept while the slots last.
      val hard = HashMap<String, MutableSet<String>>()
      val soft = HashMap<String, MutableSet<String>>()
      val core = HashSet<String>()
      for (clique in cliques) {
        val overflows = clique.size > MAX_SLOTS
        for (a in clique) {
          if (a.required !in OCCASIONAL) core.add(a.ddName)
          val hardAdjacent = hard.getOrPut(a.ddName) { HashSet() }
          val softAdjacent = soft.getOrPut(a.ddName) { HashSet() }
          for (b in clique) {
            if (a.ddName == b.ddName) continue
            if (overflows && a.required in OCCASIONAL && b.required in OCCASIONAL) {
              softAdjacent.add(b.ddName)
            } else {
              hardAdjacent.add(b.ddName)
            }
          }
        }
      }
      for ((tag, adjacent) in soft) adjacent.removeAll(hard.getValue(tag))
      val cliqueCount = hard.keys.associateWith { tag -> cliques.count { c -> c.any { it.ddName == tag } } }
      val order =
        hard.keys.sortedWith(
          compareBy<String> { it !in core }.thenByDescending { cliqueCount.getValue(it) }.thenBy { it }
        )
      val slots = HashMap<String, Int>()
      val sharing = HashSet<String>()
      for (tag in order) {
        val taken = hard.getValue(tag).mapNotNull { slots[it] }.toSet()
        val softTaken = soft.getValue(tag).mapNotNull { slots[it] }
        val free = (0 until MAX_SLOTS).filter { it !in taken }
        require(free.isNotEmpty()) {
          "coloring needs more than $MAX_SLOTS slots at '$tag'; one occupancy long holds $MAX_SLOTS. " +
            "Only tags that are optional or opt_in on a span type may share slots there."
        }
        val slot = free.minBy { s -> softTaken.count { it == s } * MAX_SLOTS + s }
        if (slot in softTaken) {
          sharing.add(tag)
          soft.getValue(tag).filter { slots[it] == slot }.forEach { sharing.add(it) }
        }
        slots[tag] = slot
      }
      return slots to sharing
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

    /**
     * A concept's names must resolve back to one id: on spans of one kind, a Datadog name, and an
     * OpenTelemetry name, resolve to at most one concept. A concept's names are left out of the
     * direction-free lookup, so none may also be a name of an ordinary tag.
     */
    private fun validateProjections(tags: List<Tag>) {
      val datadogOwner = HashMap<Pair<TagConventions.SpanKind, String>, String>()
      val otelOwner = HashMap<Pair<TagConventions.SpanKind, String>, String>()
      val plainNames =
        tags.filter { !it.concept }.flatMap { listOfNotNull(it.ddName, it.otelName) + it.scopedOtelNames.values }.toSet()
      for (t in tags.filter { it.concept }) {
        for (kind in t.resolvesOn) {
          val dd = datadogOwner.put(kind to t.datadogName, t.name)
          require(dd == null) {
            "Datadog name '${t.datadogName}' resolves to both '$dd' and '${t.name}' on ${kind.yamlKey} spans"
          }
          t.otelName?.let { otel ->
            val prev = otelOwner.put(kind to otel, t.name)
            require(prev == null) {
              "OpenTelemetry name '$otel' resolves to both '$prev' and '${t.name}' on ${kind.yamlKey} spans"
            }
          }
        }
        for (name in listOfNotNull(t.ddName, t.datadogName, t.otelName)) {
          require(name !in plainNames) {
            "'$name', a name of concept '${t.name}', is also the name of another tag"
          }
        }
      }
    }

    private val REQUIREMENT_STRENGTH = listOf("required", "conditional", "recommended", "optional")

    /** The strongest of a name's requirement levels across its declarations per direction. */
    private fun strongestRequirement(levels: List<String>): String = levels.minBy { REQUIREMENT_STRENGTH.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    /**
     * Within each direction, a rename must belong to a single tag. A direction-free rename can't
     * be a Datadog name too, since without a direction its name would resolve to two tags. A scoped
     * rename may share a name across directions -- `server.address` is `http.hostname` inbound and
     * `peer.hostname` outbound -- and may be another tag's Datadog name (see [claimSharedNames]).
     */
    private fun validateOtelNames(tags: List<Tag>) {
      val canonical = tags.map { it.ddName }.toSet()
      val owner = HashMap<Pair<TagConventions.Direction, String>, String>()
      for (t in tags) {
        if (t.concept) continue // a concept's names are shared on purpose; see validateProjections
        val byDirection =
          t.otelName?.let { otel -> TagConventions.Direction.entries.associateWith { otel } } ?: t.scopedOtelNames
        for ((direction, otel) in byDirection) {
          require(otel != t.ddName && (t.otelName == null || otel !in canonical)) {
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
