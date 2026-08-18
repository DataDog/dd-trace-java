package datadog.gradle.plugin.tags

/**
 * Parsed tag-conventions domain model + the per-type tag-set resolver. Language-agnostic: it knows
 * only structure (extends / include / applies) and per-tag semantics (name / type / required /
 * source). Id assignment and emission are layered on top of the resolved sets.
 */
class TagConventions
private constructor(
  private val spanTypes: Map<String, SpanType>,
  private val mixins: Map<String, Mixin>,
  private val traceLevel: List<Tag>,
) {
  /** A tag declaration (domain semantics only). */
  data class Tag(
    val name: String,
    val type: String,
    val required: String,
    /**
     * The tag's OpenTelemetry-namespace RENAME, or null when it has none. otel-name is optional and
     * tri-state in the YAML: absent => the OpenTelemetry name is implicitly the dd-name (pass-through
     * under the Datadog name; the RFC "retain" default) and this field is null; a name => a rename to
     * that OpenTelemetry-namespace name; the literal `none` => Datadog-only (no OpenTelemetry name)
     * and this field is null — a reserved value with no tags today (suppression is a follow-on), so
     * it currently behaves as pass-through, indistinguishable from absent. keyOf resolves a rename
     * to this tag's canonical id (inbound, many->one); openTelemetryNameOf recovers it (outbound).
     */
    val otelName: String? = null,
  )

  data class SpanType(
    val name: String,
    val abstract: Boolean,
    val extends: String?,
    val include: List<String>,
    val tags: List<Tag>,
  )

  data class Mixin(
    val name: String,
    val appliesAll: Boolean,
    val appliesTo: Set<String>,
    val tags: List<Tag>,
  )

  /** Concrete (instantiable) span types — the ones a layout is computed for. */
  fun concreteTypes(): List<String> =
    spanTypes.values.filter { !it.abstract }.map { it.name }.sorted()

  /**
   * resolved(type) = own tags + tags up the `extends` chain (incl. base) + tags of every mixin the
   * type or an ancestor `include`s + tags of every mixin whose `applies` matches. De-duped by tag
   * name (first occurrence wins). Base-first order, so it is stable across runs.
   */
  fun resolve(typeName: String): List<Tag> {
    val result = LinkedHashMap<String, Tag>()
    fun add(t: Tag) = result.putIfAbsent(t.name, t)

    val chain = ArrayList<SpanType>()
    var cur: SpanType? = spanTypes[typeName]
    while (cur != null) {
      chain.add(cur)
      cur = cur.extends?.let { spanTypes[it] }
    }
    for (st in chain.asReversed()) {
      st.tags.forEach { add(it) }
      for (mixinName in st.include) mixins[mixinName]?.tags?.forEach { add(it) }
    }
    val chainNames = chain.map { it.name }.toSet()
    for (mx in mixins.values) {
      if (mx.appliesAll || mx.appliesTo.any { it in chainNames }) mx.tags.forEach { add(it) }
    }
    return result.values.toList()
  }

  /** The explicit trace-level tier tags (their own TagMap "type" on the TraceSegment). */
  fun traceLevelTags(): List<Tag> = traceLevel

  /** A declaration group: the source that *declares* a set of tags (its own `tags:` list). */
  data class Group(val name: String, val kind: String, val tags: List<Tag>)

  /**
   * The declaration groups, in a stable order: the trace-level tier first, then every span type
   * (abstract included — `base`/`http` declare real tags) sorted by name, then every mixin sorted by
   * name. Each maps to one `group-decl`. A tag is *declared* once (in its own container's `tags:`);
   * the same tag reached via extends/include/applies is not re-declared, so first-declaration (in
   * this order) is its home group. Groups with no declared tags are omitted.
   */
  fun declarationGroups(): List<Group> {
    val groups = ArrayList<Group>()
    if (traceLevel.isNotEmpty()) groups.add(Group(TRACE_LAYER, "trace", traceLevel))
    for (name in spanTypes.keys.sorted()) {
      val st = spanTypes.getValue(name)
      if (st.tags.isNotEmpty()) groups.add(Group(name, "span_type", st.tags))
    }
    for (name in mixins.keys.sorted()) {
      val mx = mixins.getValue(name)
      if (mx.tags.isNotEmpty()) groups.add(Group(name, "mixin", mx.tags))
    }
    return groups
  }

  /** Full stored-tag universe (concrete span types' resolves + trace-level), de-duped by name. */
  fun allStoredTags(): List<Tag> {
    val union = LinkedHashMap<String, Tag>()
    for (type in concreteTypes()) for (t in resolve(type)) union.putIfAbsent(t.name, t)
    for (t in traceLevel) union.putIfAbsent(t.name, t)
    return union.values.toList()
  }

  /**
   * Full composition for a type as (origin, tag) pairs, in composition order and NOT de-duped, so a
   * tag contributed by more than one source shows up more than once. Origin is the contributing
   * span type (via extends), `incl:<mixin>` (via include), or `appl:<mixin>` (via applies).
   */
  fun compose(typeName: String): List<Pair<String, Tag>> {
    val out = ArrayList<Pair<String, Tag>>()
    val chain = ArrayList<SpanType>()
    var cur: SpanType? = spanTypes[typeName]
    while (cur != null) {
      chain.add(cur)
      cur = cur.extends?.let { spanTypes[it] }
    }
    for (st in chain.asReversed()) {
      st.tags.forEach { out.add(st.name to it) }
      for (mixinName in st.include) mixins[mixinName]?.tags?.forEach { out.add("incl:$mixinName" to it) }
    }
    val chainNames = chain.map { it.name }.toSet()
    for (mx in mixins.values) {
      if (mx.appliesAll || mx.appliesTo.any { it in chainNames }) {
        mx.tags.forEach { out.add("appl:${mx.name}" to it) }
      }
    }
    return out
  }

  companion object {
    /** Group name of the trace-level tier (its own TagMap layer on the TraceSegment). */
    const val TRACE_LAYER = "<trace>"

    @Suppress("UNCHECKED_CAST")
    fun parse(root: Map<String, Any?>): TagConventions {
      val spanTypesRaw = (root["span_types"] as? Map<String, Any?>) ?: emptyMap()
      val spanTypes =
        spanTypesRaw.mapValues { (name, v) ->
          val m = v as Map<String, Any?>
          SpanType(
            name = name,
            abstract = (m["abstract"] as? Boolean) ?: false,
            extends = m["extends"] as? String,
            include = (m["include"] as? List<String>) ?: emptyList(),
            tags = tagList(m["tags"]),
          )
        }

      val mixinsRaw = (root["mixins"] as? Map<String, Any?>) ?: emptyMap()
      val mixins =
        mixinsRaw.mapValues { (name, v) ->
          val m = v as Map<String, Any?>
          val applies = m["applies"]
          Mixin(
            name = name,
            appliesAll = applies == "all",
            appliesTo = if (applies is List<*>) applies.map { it.toString() }.toSet() else emptySet(),
            tags = tagList(m["tags"]),
          )
        }

      // Trace-level tags pass through under their Datadog name for now; their OTel mapping (resource
      // attributes) is a follow-on. TODO(otel follow-on).
      val traceLevel = tagList((root["trace_level"] as? Map<String, Any?>)?.get("tags"))
      validateOtelNameConsistency(spanTypes, mixins, traceLevel)
      return TagConventions(spanTypes, mixins, traceLevel)
    }

    /**
     * A tag is de-duped by name across span types / mixins (see [resolve] / [allStoredTags]), so its
     * whole identity — including the OpenTelemetry name — must be declared consistently everywhere it
     * appears. `http.url` on `http.server` and `http.client`, for instance, is ONE tag: it can carry
     * exactly one otel-name. Without this check, two conflicting declarations would silently collapse
     * to whichever the dedup happened to keep. Fail the build loudly instead. (A span-kind-dependent
     * mapping is a derivation, not a rename, and belongs to the derivation layer — not two otel-names
     * on one identity.)
     */
    private fun validateOtelNameConsistency(
      spanTypes: Map<String, SpanType>,
      mixins: Map<String, Mixin>,
      traceLevel: List<Tag>,
    ) {
      val declared = HashMap<String, String?>() // name -> otelName from its first declaration
      val declaredKeys = HashSet<String>()
      val check = { t: Tag ->
        if (declaredKeys.add(t.name)) {
          declared[t.name] = t.otelName
        } else {
          require(declared[t.name] == t.otelName) {
            "tag '${t.name}' declares conflicting otel-name: '${declared[t.name] ?: "none"}' vs " +
                "'${t.otelName ?: "none"}'. A tag is one identity across span types/mixins and may " +
                "carry only one otel-name; a span-kind-dependent mapping belongs to the derivation layer."
          }
        }
      }
      spanTypes.values.forEach { it.tags.forEach(check) }
      mixins.values.forEach { it.tags.forEach(check) }
      traceLevel.forEach(check)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tagList(tags: Any?): List<Tag> =
      (tags as? List<Map<String, Any?>>)?.map { m ->
        Tag(
          name = m["dd-name"].toString(),
          type = (m["type"] as? String) ?: "string",
          required = (m["required"] as? String) ?: "optional",
          otelName = parseOtelName(m),
        )
      } ?: emptyList()

    /**
     * Parse the optional, tri-state `otel-name` of one tag. Absent (key not present) => implicit
     * dd-name (pass-through) => null; the literal `none` => Datadog-only (reserved) => null; any other
     * non-blank string => a rename => that value. A present-but-invalid value (empty/blank, or a
     * non-string such as a number or an unquoted YAML `null`) is a typo that would otherwise slip
     * through the `as? String` cast into a silent pass-through or an empty rename — fail the build
     * loudly instead.
     */
    private fun parseOtelName(m: Map<String, Any?>): String? {
      if (!m.containsKey("otel-name")) return null // absent => pass-through
      val raw = m["otel-name"]
      require(raw is String && raw.isNotBlank()) {
        "tag '${m["dd-name"]}' has an invalid otel-name: '$raw'. Use a non-empty name, the literal " +
            "`none`, or omit the key entirely for pass-through under the Datadog name."
      }
      return raw.takeUnless { it == "none" }
    }
  }
}
