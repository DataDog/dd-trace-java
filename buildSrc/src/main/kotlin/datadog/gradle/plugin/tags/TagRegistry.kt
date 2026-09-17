package datadog.gradle.plugin.tags

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
    val name: String,
    val type: String,
    val required: String,
    val serial: Int,
    val traceLevel: Boolean,
    val id: Long,
    val otelName: String? = null,
    /**
     * True when this tracer routes the tag on the set-path -- the INTERCEPTED flag is set in [id].
     * Says nothing about whether the tag is also STORED: that is decided per call from the value
     * (`http.url` is routed and stored; `manual.keep` is consumed only when its value coerces to a
     * boolean), so it is not a property of the tag at all.
     */
    val intercepted: Boolean = false,
    /**
     * True for a tag declared by the per-language overlay (a set-path ROUTING identity) rather than
     * by the domain spec. Affects nothing about the id -- an overlay tag's id is an ordinary
     * identity -- it only records where the declaration came from, so the reports can show the two
     * blocks apart and the overlap guard has something to check.
     */
    val overlay: Boolean = false,
  )

  companion object {
    const val FIRST_SERIAL = 1
    const val LEVEL_TRACE = 1L shl 2 // low-32 carve bit 2; mirrors KnownTagCodec.LEVEL_TRACE
    const val INTERCEPTED = 1L shl 3 // low-32 carve bit 3; mirrors KnownTagCodec.INTERCEPTED
    const val TRACE_LAYER = "<trace>"

    /**
     * The `required` grade recorded for an overlay tag. A reserved key has no storage grade -- it is
     * an identity for set-path dispatch -- so it gets its own value rather than being filed under
     * `optional`, which would read as "stored, but rarely".
     */
    const val RESERVED = "reserved"

    /**
     * Mirrors KnownTagCodec.makeTagId(serial) + traceLevel() -- must stay in sync. LEVEL_TRACE at
     * bit 2, other low bits and the reserved [47-32] window zero.
     */
    fun encode(serial: Int, traceLevel: Boolean, intercepted: Boolean = false): Long {
      var id = serial.toLong() shl 48
      if (traceLevel) id = id or LEVEL_TRACE
      if (intercepted) id = id or INTERCEPTED
      return id
    }

    fun build(conv: TagConventions): TagRegistry = build(conv, TagOverlay.empty())

    /**
     * Assigns serials over the domain declarations and then the overlay's reserved keys.
     *
     * <p>Domain tags are numbered FIRST, sorted by name, exactly as they are without an overlay. So
     * the domain block's serials -- and therefore its ids and its generated output -- stay a pure
     * function of tag-conventions.yaml alone: adding a Java-only reserved key cannot renumber the
     * shared spec. Overlay serials continue from there, also sorted by name, so they too are stable
     * against anything but a change to the overlay itself.
     */
    fun build(conv: TagConventions, overlay: TagOverlay): TagRegistry {
      val traceNames = conv.traceLevelTags().map { it.name }.toSet()
      val routedDomain = overlay.intercepted.toSet()

      // Stable order (by name) so serials -- and therefore ids -- are a pure function of the input.
      val domain =
        conv.allDeclaredTags().sortedBy { it.name }.mapIndexed { i, t ->
          val serial = FIRST_SERIAL + i
          val traceLevel = t.name in traceNames
          val intercepted = t.name in routedDomain
          Tag(
            t.name,
            t.type,
            t.required,
            serial,
            traceLevel,
            id = encode(serial, traceLevel, intercepted),
            otelName = t.otelName,
            intercepted = intercepted)
        }

      validateNoOverlap(domain, overlay)
      validateIntercepted(domain, overlay)

      val reserved =
        overlay.reserved.sortedBy { it.name }.mapIndexed { i, t ->
          val serial = FIRST_SERIAL + domain.size + i
          Tag(
            t.name,
            t.type,
            RESERVED,
            serial,
            traceLevel = false,
            id = encode(serial, traceLevel = false, intercepted = true),
            otelName = null,
            intercepted = true,
            overlay = true)
        }

      val tags = domain + reserved
      validateOtelNames(tags)
      return TagRegistry(tags)
    }

    /**
     * A reserved key that the domain spec already declares. Both declarations are for one tag, so the
     * overlay's would mint a SECOND id for it -- two identities, and dispatch would key off whichever
     * the caller happened to resolve. The eight interceptor keys that are domain tags
     * (db.statement, service, peer.service, servlet.context, http.status_code, http.method,
     * http.url, span.kind) must therefore be absent from the overlay, and this is what enforces it.
     *
     * <p>An OpenTelemetry name counts as taken too: keyOf is many->one, so a reserved key colliding
     * with a domain tag's otel-name would make keyOf(name) ambiguous in exactly the same way.
     */
    private fun validateNoOverlap(domain: List<Tag>, overlay: TagOverlay) {
      val byName = domain.associateBy { it.name }
      val byOtel = domain.mapNotNull { t -> t.otelName?.let { it to t.name } }.toMap()
      for (t in overlay.reserved) {
        require(t.name !in byName) {
          "reserved key '${t.name}' is already declared in the domain spec (tag-conventions.yaml), " +
              "so it already has an id; declaring it again in the overlay would mint a second " +
              "identity for one tag. Remove it from the overlay."
        }
        byOtel[t.name]?.let { canonical ->
          throw IllegalArgumentException(
            "reserved key '${t.name}' collides with the OpenTelemetry name of domain tag " +
                "'$canonical', so keyOf('${t.name}') would have two answers.")
        }
      }
    }

    /**
     * Every `intercepted` name must actually BE a domain tag. A typo there would otherwise flag
     * nothing at all: the name matches no declaration, no id gets the INTERCEPTED bit, and the
     * set-path pre-screen silently stops recognising a key the interceptor still handles. That is a
     * behaviour change with no error message, which is the worst shape this file can fail in.
     */
    private fun validateIntercepted(domain: List<Tag>, overlay: TagOverlay) {
      val names = domain.map { it.name }.toSet()
      for (n in overlay.intercepted) {
        require(n in names) {
          "intercepted names '$n', which is not declared in the domain spec " +
              "(tag-conventions.yaml). Use the tag's canonical dd-name; a key with no domain " +
              "declaration belongs under `routed:` instead."
        }
      }
    }

    /**
     * An OpenTelemetry name must be unambiguous: it may not collide with any canonical tag name, nor
     * be claimed by two different tags. Otherwise keyOf(otelName) would have no single right answer.
     * Fail the build loudly rather than silently pick a winner.
     */
    private fun validateOtelNames(tags: List<Tag>) {
      val canonical = tags.map { it.name }.toSet()
      val owner = HashMap<String, String>()
      for (t in tags) {
        val otel = t.otelName ?: continue
        require(otel !in canonical) {
          "OpenTelemetry name '$otel' (of '${t.name}') collides with canonical tag name '$otel'"
        }
        val prev = owner.put(otel, t.name)
        require(prev == null) {
          "OpenTelemetry name '$otel' is claimed by both '$prev' and '${t.name}'"
        }
      }
    }
  }
}
