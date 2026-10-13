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
    const val LEVEL_TRACE = 1L shl 2 // low-32 carve bit 2; mirrors KnownTagCodec.LEVEL_TRACE
    const val INTERCEPTED = 1L shl 1 // low-32 carve bit 1; mirrors KnownTagCodec.INTERCEPTED

    /**
     * Mirrors KnownTagCodec.makeTagId(serial) + traceLevel() + intercepted() -- must stay in sync.
     * LEVEL_TRACE at bit 2, INTERCEPTED at bit 1, other low bits and the reserved [47-32] window
     * zero.
     */
    fun encode(serial: Int, traceLevel: Boolean, intercepted: Boolean): Long {
      var id = serial.toLong() shl 48
      if (traceLevel) id = id or LEVEL_TRACE
      if (intercepted) id = id or INTERCEPTED
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
            id = encode(serial, isTraceLevel, isIntercepted),
            otelName = otelName,
            scopedOtelNames = scoped,
          )
        }

      validateOtelNames(tags)
      return TagRegistry(tags)
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
    private fun strongestRequirement(levels: List<String>): String =
      levels.minBy { REQUIREMENT_STRENGTH.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }

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
