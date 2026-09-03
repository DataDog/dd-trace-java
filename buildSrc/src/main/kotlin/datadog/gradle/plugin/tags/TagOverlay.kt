package datadog.gradle.plugin.tags

/**
 * Parsed per-language overlay (`tag-conventions-java.yaml`): the keys that exist only because this
 * tracer ROUTES them on the set-path, and so need an identity to dispatch on but no place in the
 * language-agnostic domain spec.
 *
 * <p>Deliberately separate from [TagConventions] rather than a section of it. The domain model's
 * value is that it knows only structure and semantics; folding one language's routing vocabulary
 * into it would make it not that. Composition happens in [TagRegistry], which is already the layer
 * that turns declarations into ids.
 *
 * <p>An overlay tag carries a name and a type and nothing else. It has no `required` grade (that
 * grades how a tag is STORED, and a reserved key is an identity for dispatch), no `otel-name` (a
 * domain concern), and — pointedly — no flag saying whether it is also stored, because that is
 * decided per call from the value. See the file header and KnownTagCodec for why a static bit there
 * is drift rather than information.
 */
class TagOverlay
private constructor(
  val reserved: List<Tag>,
  /**
   * Names of DOMAIN tags this tracer also routes. Names, not declarations: the tag's identity comes
   * from the domain spec and is not duplicated here -- being listed only adds the INTERCEPTED flag
   * to the id it already has.
   */
  val intercepted: List<String>,
) {
  /** One reserved key: an identity for set-path dispatch. */
  data class Tag(val name: String, val type: String)

  companion object {
    /** An overlay with nothing in it — the shape used when a language declares no reserved keys. */
    fun empty(): TagOverlay = TagOverlay(emptyList(), emptyList())

    @Suppress("UNCHECKED_CAST")
    fun parse(root: Map<String, Any?>): TagOverlay {
      val raw = (root["reserved"] as? Map<String, Any?>)?.get("tags") as? List<Map<String, Any?>>
      val decls = raw ?: emptyList()
      decls.forEach { rejectDomainFields(it) }
      val tags = decls.map { Tag(name = parseDdName(it), type = (it["type"] as? String) ?: "string") }
      validateNoDuplicates(tags)
      val intercepted = parseIntercepted(root)
      return TagOverlay(tags, intercepted)
    }

    /**
     * The `intercepted` list: plain domain tag names, so a string list rather than declarations.
     * A non-string entry (an accidental `{ dd-name: x }` mapping, say) must fail rather than
     * `toString()` into a name that matches no domain tag and then silently flags nothing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseIntercepted(root: Map<String, Any?>): List<String> {
      val raw = (root["intercepted"] as? Map<String, Any?>)?.get("tags") as? List<Any?>
      val names =
        (raw ?: emptyList()).map { e ->
          require(e is String && e.isNotBlank()) {
            "intercepted entry is not a tag name: '$e'. List domain tag names as plain strings; " +
                "a tag that needs its own identity goes under `reserved:` instead."
          }
          e
        }
      val seen = HashSet<String>()
      for (n in names) require(seen.add(n)) { "intercepted names '$n' more than once" }
      return names
    }

    /**
     * The same routing key declared twice. Harmless to the id assignment (the union de-dupes), but
     * it means one of the two declarations is dead and nobody can tell which was intended.
     */
    private fun validateNoDuplicates(tags: List<Tag>) {
      val seen = HashSet<String>()
      for (t in tags) {
        require(seen.add(t.name)) { "overlay declares reserved key '${t.name}' more than once" }
      }
    }

    /**
     * Domain-only fields on an overlay tag. `required` grades how a tag is STORED and `otel-name` is
     * a cross-language naming decision; neither means anything for a routing identity. Ignoring them
     * silently would let someone believe they had graded a reserved key as dense, or given it an
     * OpenTelemetry name that nothing will ever emit. A key that genuinely needs either belongs in
     * the domain spec.
     */
    private fun rejectDomainFields(m: Map<String, Any?>) {
      for (key in DOMAIN_ONLY_FIELDS) {
        require(!m.containsKey(key)) {
          "reserved key '${m["dd-name"]}' declares '$key', which is a domain-spec field and has no " +
              "meaning for a set-path routing identity. Declare the tag in tag-conventions.yaml if " +
              "it needs one."
        }
      }
    }

    private val DOMAIN_ONLY_FIELDS = listOf("required", "otel-name")

    /** Mirrors [TagConventions] — a missing or non-string name would flow on as the literal "null". */
    private fun parseDdName(m: Map<String, Any?>): String {
      val raw = m["dd-name"]
      require(raw is String && raw.isNotBlank()) { "reserved key declaration has no valid dd-name: $m" }
      return raw
    }
  }
}
