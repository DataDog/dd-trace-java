package datadog.buildlogic.tagRegistry

/**
 * Assigns tag ids from a parsed [TagConventions]. The id encoding mirrors KnownTagCodec: [63-48
 * serial][47-32 reserved][31-0 flags].
 *
 * <p>An id is IDENTITY only: a globally unique serial plus the trace-level classification bit. It
 * carries no storage-layout coordinate -- bits [47-32] are held vacant for the co-occurrence slot
 * that the dense tag store assigns by graph coloring, which lands with the dense store itself.
 * Nothing here needs to know how (or whether) a tag is stored.
 *
 * <p>Nor does anything here know how a tag is SET. Whether the tracer intercepts a tag on the
 * set-path (routing it to a span field or a sampling directive instead of tag storage) is a
 * property of TagInterceptor, not of the tag's identity, and modelling it was the source of a whole
 * class of drift between this registry and the interceptor's actual switch. It arrives with the
 * work that consumes it -- the id->handler dispatch table that retires TagInterceptor -- where the
 * interceptor can be the authority. Re-adding a classification bit then is purely additive.
 */
class TagRegistry private constructor(val tags: List<Tag>) {
  data class Tag(
    /** The tag's identity: its Datadog name, or `<dd-name>@<direction>` when declared per direction. */
    val name: String,
    val type: String,
    val required: String,
    val serial: Int,
    val traceLevel: Boolean,
    val id: Long,
    /** The tag's OpenTelemetry name, in [otelDirection] or every direction; null when not renamed. */
    val declaredOtelName: String? = null,
    /**
     * The one direction [declaredOtelName] applies in, or null when it applies in every direction. A
     * tag has one declaration, so its rename covers either every direction or exactly one.
     */
    val otelDirection: TagConventions.Direction? = null,
    /** The Datadog-namespace name, shared by the tags of a name declared per direction. */
    val ddName: String = name,
    /** The direction of a tag whose Datadog name is shared by a tag per direction, else null. */
    val sharedNameDirection: TagConventions.Direction? = null,
  ) {
    /**
     * The OpenTelemetry name that applies in every direction, or null when the tag has no rename or
     * its rename is scoped to a direction, which is not applied until name resolution knows it.
     */
    val otelName: String? = declaredOtelName.takeIf { otelDirection == null }
  }

  companion object {
    const val FIRST_SERIAL = 1
    const val LEVEL_TRACE = 1L shl 2 // low-32 carve bit 2; mirrors KnownTagCodec.LEVEL_TRACE

    /**
     * Serials stay below 2^15, so bit 63 of an id is never set: every id is positive, clear of 0
     * (unknown) and of KnownTagCodec's negative lookup sentinels.
     */
    const val SERIAL_LIMIT = 1 shl 15

    /**
     * Mirrors KnownTagCodec.makeTagId(serial) + traceLevel() -- must stay in sync. LEVEL_TRACE at
     * bit 2, other low bits and the reserved [47-32] window zero. Every id is made here, and the
     * range check makes each one positive by construction.
     */
    fun encode(serial: Int, traceLevel: Boolean): Long {
      require(serial in FIRST_SERIAL until SERIAL_LIMIT) {
        "serial $serial is outside [$FIRST_SERIAL, $SERIAL_LIMIT): more tags would make tag ids " +
          "negative, where KnownTagCodec's lookup sentinels live"
      }
      var id = serial.toLong() shl 48
      if (traceLevel) id = id or LEVEL_TRACE
      return id
    }

    fun build(conv: TagConventions): TagRegistry {
      val traceNames = conv.traceLevelTags().map { it.name }.toSet()
      val renames = conv.otelMappings().associateBy { it.tag }

      // Stable order (by name) so serials -- and therefore ids -- are a pure function of the input.
      val tags =
        conv.allDeclaredTags().sortedBy { it.name }.mapIndexed { i, t ->
          val serial = FIRST_SERIAL + i
          val traceLevel = t.name in traceNames
          Tag(
            t.name,
            t.type,
            t.required,
            serial,
            traceLevel,
            id = encode(serial, traceLevel),
            declaredOtelName = renames[t.name]?.otelName,
            otelDirection = renames[t.name]?.direction,
            ddName = t.ddName,
            sharedNameDirection = t.sharedNameDirection,
          )
        }

      validateOtelNames(tags)
      return TagRegistry(tags)
    }

    /**
     * An OpenTelemetry name must be unambiguous in each direction: it may not collide with any
     * canonical tag name, nor be claimed by two tags on spans of the same direction, or resolving it
     * would have no single answer. Two tags may share a name across directions: `server.address` is
     * `http.hostname` on inbound spans and `peer.hostname` on outbound ones.
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
