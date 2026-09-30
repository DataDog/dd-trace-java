package datadog.buildlogic.tagRegistry

/**
 * Parsed tag-conventions domain model + the per-type tag-set resolver. Language-agnostic: it knows
 * only structure (extends / include / applies) and per-tag semantics (name / type / required /
 * source). Id assignment and emission are layered on top of the resolved sets.
 */
class TagConventions private constructor(
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
    /**
     * Author's assertion that [otelName] means this tag on every span kind the OpenTelemetry
     * attribute appears on. Required for a rename declared on a concrete span type; see
     * [validateOtelNameScope].
     */
    val spanKindNeutral: Boolean = false,
  )

  /**
   * A `{ ref: <dd-name>, required: <level> }` entry: uses a tag declared elsewhere, optionally at a
   * different requirement level. Identity (type, otel-name) comes only from the one declaration.
   */
  data class Ref(val name: String, val required: String?)

  data class SpanType(
    val name: String,
    val abstract: Boolean,
    val extends: String?,
    val include: List<String>,
    val tags: List<Tag>,
    val refs: List<Ref> = emptyList(),
  )

  data class Mixin(
    val name: String,
    val appliesAll: Boolean,
    val appliesTo: Set<String>,
    val tags: List<Tag>,
    val refs: List<Ref> = emptyList(),
  )

  private val declarations: Map<String, Tag> by lazy {
    allDeclaredTags().associateBy { it.name }
  }

  /** The tag a [Ref] names, at the ref's requirement level when it overrides one. */
  private fun materialize(ref: Ref): Tag {
    val decl = declarations.getValue(ref.name)
    return if (ref.required == null) decl else decl.copy(required = ref.required)
  }

  /** Concrete (instantiable) span types — the ones a layout is computed for. */
  fun concreteTypes(): List<String> = spanTypes.values.filter { !it.abstract }.map { it.name }.sorted()

  /**
   * resolved(type) = own tags + tags up the `extends` chain (incl. base) + tags of every mixin the
   * type or an ancestor `include`s + tags of every mixin whose `applies` matches. De-duped by tag
   * name (first occurrence wins). Base-first order, so it is stable across runs. A [Ref] adds its
   * tag if absent and otherwise overrides only the requirement level, keeping the tag's position;
   * refs are applied after a type's own tags and includes, so the most derived type wins.
   */
  fun resolve(typeName: String): List<Tag> {
    val result = LinkedHashMap<String, Tag>()
    fun add(t: Tag) = result.putIfAbsent(t.name, t)
    fun applyRef(r: Ref) {
      val current = result[r.name]
      // Re-putting an existing key keeps its LinkedHashMap position.
      result[r.name] =
        when {
          current == null -> materialize(r)
          r.required == null -> current
          else -> current.copy(required = r.required)
        }
    }

    val chain = ArrayList<SpanType>()
    var cur: SpanType? = spanTypes[typeName]
    while (cur != null) {
      chain.add(cur)
      cur = cur.extends?.let { spanTypes[it] }
    }
    for (st in chain.asReversed()) {
      st.tags.forEach { add(it) }
      for (mixinName in st.include) {
        mixins[mixinName]?.let { mx ->
          mx.tags.forEach { add(it) }
          mx.refs.forEach { applyRef(it) }
        }
      }
      st.refs.forEach { applyRef(it) }
    }
    val chainNames = chain.map { it.name }.toSet()
    for (mx in mixins.values) {
      if (mx.appliesAll || mx.appliesTo.any { it in chainNames }) {
        mx.tags.forEach { add(it) }
        mx.refs.forEach { if (it.name !in result) add(materialize(it)) }
      }
    }
    return result.values.toList()
  }

  /** The explicit trace-level tier tags (their own TagMap "type" on the TraceSegment). */
  fun traceLevelTags(): List<Tag> = traceLevel

  /** Includes every declaration, even from mixins whose span types are not modeled yet. */
  fun allDeclaredTags(): List<Tag> = buildList {
    addAll(traceLevel)
    spanTypes.toSortedMap().values.forEach { addAll(it.tags) }
    mixins.toSortedMap().values.forEach { addAll(it.tags) }
  }.distinctBy { it.name }

  /**
   * Mixin `applies:` targets that name no span type modeled here, as (mixin, missing types). A tag
   * id is identity and does not depend on layout, so such a mixin's tags are still registered --
   * this is a LAYOUT gap, not lost data: the mixin contributes to no type's resolved set, so its
   * tags occupy no per-type slot until the type is modeled. Reported rather than fatal, because
   * declaring tags ahead of the span type that will carry them is a legitimate intermediate state;
   * what is not acceptable is it being invisible.
   */
  fun unmodeledAppliesTargets(): List<Pair<String, List<String>>> = mixins.values
    .sortedBy { it.name }
    .mapNotNull { mx ->
      val missing = mx.appliesTo.filter { it !in spanTypes }.sorted()
      if (missing.isEmpty()) null else mx.name to missing
    }

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun parse(root: Map<String, Any?>): TagConventions {
      for (section in listOf("span_types", "mixins", "trace_level")) {
        require(root[section] == null || root[section] is Map<*, *>) { "$section must be a mapping" }
      }
      val spanTypesRaw = (root["span_types"] as? Map<String, Any?>) ?: emptyMap()
      val spanTypes =
        spanTypesRaw.mapValues { (name, v) ->
          require(v is Map<*, *>) { "span type '$name' must be a mapping" }
          val m = v as Map<String, Any?>
          require(m["abstract"] == null || m["abstract"] is Boolean) {
            "span type '$name' abstract must be a boolean"
          }
          require(m["extends"] == null || m["extends"] is String) {
            "span type '$name' extends must be a span type name"
          }
          val include = m["include"]
          require(include == null || (include is List<*> && include.all { it is String })) {
            "span type '$name' include must be a list of mixin names"
          }
          SpanType(
            name = name,
            abstract = (m["abstract"] as? Boolean) ?: false,
            extends = m["extends"] as? String,
            include = (m["include"] as? List<String>) ?: emptyList(),
            tags = tagList(m["tags"]),
            refs = refList(m["tags"]),
          )
        }

      val mixinsRaw = (root["mixins"] as? Map<String, Any?>) ?: emptyMap()
      val mixins =
        mixinsRaw.mapValues { (name, v) ->
          require(v is Map<*, *>) { "mixin '$name' must be a mapping" }
          val m = v as Map<String, Any?>
          val applies = m["applies"]
          require(applies == null || applies == "all" || (applies is List<*> && applies.all { it is String })) {
            "mixin '$name' applies must be 'all' or a list of span types"
          }
          Mixin(
            name = name,
            appliesAll = applies == "all",
            appliesTo = if (applies is List<*>) (applies as List<String>).toSet() else emptySet(),
            tags = tagList(m["tags"]),
            refs = refList(m["tags"]),
          )
        }

      for (spanType in spanTypes.values) {
        for (included in spanType.include) {
          require(included in mixins) {
            "span type '${spanType.name}' includes unknown mixin '$included'"
          }
        }
        val visited = HashSet<String>()
        var current: SpanType? = spanType
        while (current != null) {
          val name = current.name
          require(visited.add(name)) { "span type '${spanType.name}' has cyclic extends at '$name'" }
          current = current.extends?.let { parent ->
            requireNotNull(spanTypes[parent]) { "span type '$name' extends unknown span type '$parent'" }
          }
        }
      }

      // Trace-level tags pass through under their Datadog name for now; their OTel mapping (resource
      // attributes) is a follow-on. TODO(otel follow-on).
      val traceLevelRaw = (root["trace_level"] as? Map<String, Any?>)?.get("tags")
      val traceLevel = tagList(traceLevelRaw)
      require(refList(traceLevelRaw).isEmpty()) { "trace_level tags must be declarations, not refs" }
      validateSingleDeclaration(spanTypes, mixins, traceLevel)
      validateOtelNameScope(spanTypes, mixins, traceLevel)
      return TagConventions(spanTypes, mixins, traceLevel)
    }

    /**
     * A tag is ONE identity across span types / mixins, so it is declared exactly once, with its
     * type and otel-name; every other span type that carries it uses a `ref`, which may override
     * only the requirement level. `http.url`, for instance, is declared on the shared `http` parent
     * rather than on both `http.server` and `http.client`. Rejecting a second declaration outright
     * (rather than only a conflicting one) keeps identity in one place. Refs must name a declared tag.
     */
    private fun validateSingleDeclaration(
      spanTypes: Map<String, SpanType>,
      mixins: Map<String, Mixin>,
      traceLevel: List<Tag>,
    ) {
      val home = HashMap<String, String>() // name -> declaring container
      val declare = { container: String, t: Tag ->
        val prev = home.putIfAbsent(t.name, container)
        require(prev == null) {
          "tag '${t.name}' is declared in both '$prev' and '$container'. Declare it once and use " +
            "`{ ref: ${t.name}, required: <level> }` elsewhere; a ref may override only `required`."
        }
      }
      traceLevel.forEach { declare("<trace>", it) }
      spanTypes.values.forEach { st -> st.tags.forEach { declare(st.name, it) } }
      mixins.values.forEach { mx -> mx.tags.forEach { declare("mixin ${mx.name}", it) } }

      val refs =
        spanTypes.values.flatMap { st -> st.refs.map { st.name to it } } +
          mixins.values.flatMap { mx -> mx.refs.map { "mixin ${mx.name}" to it } }
      for ((container, r) in refs) {
        require(r.name in home) { "'$container' refs undeclared tag '${r.name}'" }
      }
    }

    /**
     * TagMap canonicalizes an otel-name to its tag regardless of span kind, so a rename is only
     * correct if the OpenTelemetry attribute means this tag on EVERY span kind it appears on --
     * OTel's network.peer.address, for instance, is the client on a server span but the server on a
     * client span. A rename declared in a shared scope (trace_level, an abstract span type, a mixin)
     * already spans kinds. One declared on a concrete span type must say so explicitly with
     * `span-kind-neutral: true`, so a span-kind-specific mapping cannot slip in as a rename.
     */
    private fun validateOtelNameScope(
      spanTypes: Map<String, SpanType>,
      mixins: Map<String, Mixin>,
      traceLevel: List<Tag>,
    ) {
      val all = traceLevel + spanTypes.values.flatMap { it.tags } + mixins.values.flatMap { it.tags }
      for (t in all) {
        require(!t.spanKindNeutral || t.otelName != null) {
          "tag '${t.name}' sets span-kind-neutral without an otel-name"
        }
      }
      for (st in spanTypes.values.filter { !it.abstract }) {
        for (t in st.tags) {
          require(t.otelName == null || t.spanKindNeutral) {
            "tag '${t.name}' renames to otel-name '${t.otelName}' on concrete span type '${st.name}'. " +
              "Canonicalization ignores span kind, so either declare it in a shared scope (an " +
              "abstract parent or a mixin) or, if '${t.otelName}' means '${t.name}' on every span " +
              "kind, add `span-kind-neutral: true`."
          }
        }
      }
    }

    private val REF_KEYS = setOf("ref", "required")

    @Suppress("UNCHECKED_CAST")
    private fun refList(tags: Any?): List<Ref> = (tags as? List<Map<String, Any?>>)
      ?.filter { it.containsKey("ref") }
      ?.map { m ->
        val extra = m.keys - REF_KEYS
        require(extra.isEmpty()) {
          "ref '${m["ref"]}' may override only `required`, but also sets $extra; identity " +
            "(type, otel-name) comes from the tag's single declaration"
        }
        val name = m["ref"]
        require(name is String && name.isNotBlank()) { "ref has no valid tag name: $m" }
        require(m["required"] == null || m["required"] is String) { "ref '$name' required must be a string" }
        Ref(name, m["required"] as? String)
      } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun tagList(tags: Any?): List<Tag> {
      require(tags == null || tags is List<*>) { "tags must be a list of tag declarations" }
      return (tags as? List<*>)?.mapNotNull { entry ->
        require(entry is Map<*, *>) { "tag declaration must be a mapping" }
        val m = entry as Map<String, Any?>
        if (m.containsKey("ref")) return@mapNotNull null
        val name = parseDdName(m)
        require(m["type"] == null || m["type"] is String) { "tag '$name' type must be a string" }
        require(m["required"] == null || m["required"] is String) { "tag '$name' required must be a string" }
        Tag(
          name = name,
          type = (m["type"] as? String) ?: "string",
          required = (m["required"] as? String) ?: "optional",
          otelName = parseOtelName(m),
          spanKindNeutral = parseSpanKindNeutral(m),
        )
      } ?: emptyList()
    }

    /**
     * The mandatory `dd-name` of one tag -- its canonical Datadog name, and the key everything else
     * hangs off. A missing key or a non-string value must fail the build: `toString()` on it would
     * yield the literal "null" (or a number's rendering), which then flows on as a real tag name and
     * gets an id, a slot and an entry in the generated registry. A typo here is silent otherwise.
     */
    private fun parseDdName(m: Map<String, Any?>): String {
      val raw = m["dd-name"]
      require(raw is String && raw.isNotBlank()) { "tag declaration has no valid dd-name: $m" }
      return raw
    }

    private fun parseSpanKindNeutral(m: Map<String, Any?>): Boolean {
      if (!m.containsKey("span-kind-neutral")) return false
      val raw = m["span-kind-neutral"]
      require(raw is Boolean) { "tag '${m["dd-name"]}' has a non-boolean span-kind-neutral: '$raw'" }
      return raw
    }

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
