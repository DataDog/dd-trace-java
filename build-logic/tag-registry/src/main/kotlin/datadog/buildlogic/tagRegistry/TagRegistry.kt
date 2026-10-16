package datadog.buildlogic.tagRegistry

/**
 * Assigns tag ids from a parsed [TagConventions]. The id encoding mirrors KnownTagCodec: [63-48
 * serial][47-32 reserved][31-0 flags].
 *
 * <p>An id is a globally unique serial plus two classification bits: trace-level and intercepted. It
 * carries no storage-layout coordinate -- bits [47-32] are held vacant for the co-occurrence slot
 * that the dense tag store assigns by graph coloring, which lands with the dense store itself.
 * Nothing here needs to know how (or whether) a tag is stored.
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

    /**
     * Serials stay below 2^15, so bit 63 of an id is never set: every id is positive, clear of 0
     * (unknown) and of KnownTagCodec's negative lookup sentinels.
     */
    const val SERIAL_CAPACITY = 1 shl 15

    /**
     * Mirrors KnownTagCodec.makeTagId(serial) + intercepted() + traceLevel() -- must stay in sync.
     * HAS_SCOPED_ALIAS at bit 0 (the tag has an OpenTelemetry name in some directions only),
     * INTERCEPTED at bit 1, LEVEL_TRACE at bit 2, other low bits and the reserved [47-32] window
     * zero. Every id is made here, and the range check makes each one positive by construction.
     */
    fun encode(serial: Int, traceLevel: Boolean, intercepted: Boolean, hasScopedAlias: Boolean = false): Long {
      require(serial in FIRST_SERIAL until SERIAL_CAPACITY) {
        "serial $serial is outside [$FIRST_SERIAL, $SERIAL_CAPACITY): more tags would make tag ids " +
          "negative, where KnownTagCodec's lookup sentinels live"
      }
      var id = serial.toLong() shl 48
      if (traceLevel) id = id or LEVEL_TRACE
      if (intercepted) id = id or INTERCEPTED
      if (hasScopedAlias) id = id or HAS_SCOPED_ALIAS
      return id
    }

    fun build(conv: TagConventions): TagRegistry {
      val traceLevel = conv.traceLevelTags().map { it.ddName }.toSet()
      val renames = conv.otelMappings().groupBy { it.tag.ddName }

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
          Tag(
            first.ddName,
            first.type,
            strongestRequirement(decls.map { it.required }),
            serial,
            isTraceLevel,
            isIntercepted,
            id = encode(serial, isTraceLevel, isIntercepted, scoped.isNotEmpty()),
            otelName = otelName,
            scopedOtelNames = scoped,
          )
        }

      validateOtelNames(tags)
      return TagRegistry(claimSharedNames(tags))
    }

    /**
     * A tag whose Datadog name is also another tag's direction-scoped OpenTelemetry name keeps that
     * name in every direction the other tag doesn't claim it. `server.port` is the inbound server
     * port's own name, and `peer.port` on outbound spans: a write of `server.port` on a client span
     * is `peer.port`, and on a server or internal span the tag itself. So the name depends on the
     * span's direction like any other scoped alias, and the tag carries [HAS_SCOPED_ALIAS].
     */
    private fun claimSharedNames(tags: List<Tag>): List<Tag> {
      val claimedBy = HashMap<String, MutableSet<TagConventions.Direction>>()
      for (t in tags) {
        for ((direction, otel) in t.scopedOtelNames) {
          claimedBy.getOrPut(otel) { mutableSetOf() }.add(direction)
        }
      }
      return tags.map { t ->
        val claimed = claimedBy[t.ddName] ?: return@map t
        require(t.otelName == null && t.scopedOtelNames.isEmpty()) {
          "'${t.name}' is another tag's OpenTelemetry name on ${claimed.joinToString { it.yamlKey }} " +
            "spans, so it cannot be renamed itself"
        }
        val own = TagConventions.Direction.entries.filter { it !in claimed }.associateWith { t.ddName }
        t.copy(id = t.id or HAS_SCOPED_ALIAS, scopedOtelNames = own.toSortedMap())
      }
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
     * Within each direction, a rename must belong to a single tag. A direction-free rename can't
     * be a Datadog name too, since without a direction its name would resolve to two tags. A scoped
     * rename may share a name across directions -- `server.address` is `http.hostname` inbound and
     * `peer.hostname` outbound -- and may be another tag's Datadog name (see [claimSharedNames]).
     */
    private fun validateOtelNames(tags: List<Tag>) {
      val canonical = tags.map { it.ddName }.toSet()
      val owner = HashMap<Pair<TagConventions.Direction, String>, String>()
      for (t in tags) {
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
