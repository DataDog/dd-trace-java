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
 */
class TagRegistry private constructor(val tags: List<Tag>) {
  data class Tag(
    val identity: TagConventions.TagIdentity,
    val type: String,
    val required: String,
    val serial: Int,
    val traceLevel: Boolean,
    val intercepted: Boolean,
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

    /**
     * Mirrors KnownTagCodec.makeTagId(serial) + traceLevel() + intercepted() -- must stay in sync.
     * LEVEL_TRACE at bit 2, INTERCEPTED at bit 1, other low bits and the reserved [47-32] window zero.
     */
    fun encode(serial: Int, traceLevel: Boolean, intercepted: Boolean = false): Long {
      var id = serial.toLong() shl 48
      if (traceLevel) id = id or LEVEL_TRACE
      if (intercepted) id = id or INTERCEPTED
      return id
    }

    fun build(conv: TagConventions): TagRegistry {
      val traceLevel = conv.traceLevelTags().map { it.identity }.toSet()
      val renames = conv.otelMappings().associateBy { it.tag }

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
            id = encode(serial, isTraceLevel, isIntercepted),
            declaredOtelName = renames[t.identity]?.otelName,
            otelDirection = renames[t.identity]?.direction,
          )
        }

      validateOtelNames(tags)
      return TagRegistry(tags)
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
