package datadog.buildlogic.tagRegistry

/**
 * Parses the tag conventions and resolves each span type's tags through `extends`, `include`,
 * and `applies`. Declarations carry names, types, requirement levels, and optional OpenTelemetry
 * renames. ID assignment and source generation are handled separately.
 */
class TagConventions private constructor(
  private val spanTypes: Map<String, SpanType>,
  private val mixins: Map<String, Mixin>,
  private val traceLevel: List<Tag>,
) {
  /** One tag declaration: its Datadog name, type, requirement level, and optional rename. */
  data class Tag(
    val name: String,
    val type: String,
    val required: String,
    /**
     * Explicit OpenTelemetry name from `otel-name`, or null when no rename is configured.
     * When a rename is configured, both names resolve to the same tag ID.
     *
     * An omitted `otel-name` and the reserved literal `none` both produce null. Exporters
     * currently use the Datadog name in either case; `none` does not suppress the tag yet.
     */
    val otelName: String? = null,
    /**
     * Set `span-kind-neutral: true` for a rename declared on a concrete span type only
     * when the Datadog and OpenTelemetry names denote the same value on every span kind.
     * Name resolution ignores span kind, so the rename also applies outside that type.
     *
     * For example, `db.type` -> `db.system` on `db.client` qualifies: `db.system` only
     * ever describes a database. Renames in `trace_level`, abstract types, and mixins need
     * no flag. Setting this flag without a configured rename is invalid.
     *
     * The flag is interim: a follow-on replaces it with span-kind-aware name resolution.
     */
    val spanKindNeutral: Boolean = false,
  )

  /**
   * A `{ ref: <dd-name>, required: <level> }` entry. It reuses a tag declared elsewhere, optionally
   * at a different requirement level; its type and otel-name always come from that declaration.
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

  /** Returns the names of non-abstract span types in sorted order. */
  fun concreteTypes(): List<String> = spanTypes.values.filter { !it.abstract }.map { it.name }.sorted()

  /**
   * Resolves tags from the root ancestor to the requested type, then adds mixins whose
   * `applies` matches the type or an ancestor.
   *
   * Each type contributes its declarations, included mixins, then references. Duplicate
   * names keep their first position; references can override requirement levels. References
   * from `applies` mixins add only missing tags.
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

  /** Returns the tags declared in `trace_level`, which are set once per trace, not per span. */
  fun traceLevelTags(): List<Tag> = traceLevel

  /** Includes every declaration, even from mixins whose span types are not modeled yet. */
  fun allDeclaredTags(): List<Tag> = buildList {
    addAll(traceLevel)
    spanTypes.toSortedMap().values.forEach { addAll(it.tags) }
    mixins.toSortedMap().values.forEach { addAll(it.tags) }
  }.distinctBy { it.name }

  /**
   * Returns mixins with `applies` targets missing from `span_types`, paired with the missing names.
   *
   * These targets are reported in `resolved-tags.txt` without failing generation. The mixin's
   * tags are still registered, and any matching declared span types still receive them.
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
     * Rejects multiple declarations of the same `dd-name`, including identical declarations.
     * Declare shared tags once on a parent or mixin and reuse them through `ref` entries,
     * which may override only `required`. References must name a declared tag.
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
     * Requires `span-kind-neutral: true` for renames on concrete span types because name
     * resolution ignores span kind. Validation checks the flag and requires a configured
     * rename, relying on the author's semantic check. Shared scopes do not require the flag.
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

    /** Reads the required, nonblank string `dd-name`; invalid values fail generation. */
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
     * Reads `otel-name`: an omitted key or `none` returns null; otherwise returns a nonblank string.
     * Rejects explicit null, blank strings, and non-string values so typos cannot disable a rename.
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
