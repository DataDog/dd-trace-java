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
     * Set `span-kind-neutral: true` to apply a rename declared on a typed span type in every
     * direction, not only in that type's. It confirms OpenTelemetry uses the name only for this tag:
     * `db.type` -> `db.system` on `db.client` qualifies, because `db.system` only describes a
     * database. Until name resolution knows a span's direction, only renames that apply in every
     * direction are used, so an unmarked typed rename is recorded but not yet applied.
     *
     * A rename on a concrete span type with no `span-kind` requires the flag. Renames in
     * `trace_level`, abstract types, and mixins need none. Setting it without a plain rename is
     * invalid.
     *
     * The flag is interim: it goes away once name resolution is direction-aware.
     */
    val spanKindNeutral: Boolean = false,
    /**
     * Direction-specific OpenTelemetry names from the map form of `otel-name`, for a tag whose meaning
     * flips with span direction, such as `peer.port`: `{ outbound: server.port, inbound: client.port }`.
     * Allowed only in a `frame: relative` mixin.
     */
    val otelByDirection: Map<Direction, String> = emptyMap(),
  )

  /**
   * Span direction, derived from `span-kind`: `server` and `consumer` are inbound, `client` and
   * `producer` are outbound, and `internal` has no direction.
   */
  enum class Direction(val yamlKey: String) {
    INBOUND("inbound"),
    OUTBOUND("outbound"),
    NONE("none"),
  }

  /** One OpenTelemetry name for [tag] on spans of [direction]. */
  data class OtelMapping(val tag: String, val direction: Direction, val otelName: String)

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
    val spanKind: String? = null,
  )

  data class Mixin(
    val name: String,
    val appliesAll: Boolean,
    val appliesTo: Set<String>,
    val tags: List<Tag>,
    val refs: List<Ref> = emptyList(),
    /** `relative` for a mixin whose tags name the other end of the connection, such as `peer.*`. */
    val frame: String? = null,
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

  /**
   * Returns every OpenTelemetry name per direction. A plain `otel-name` in a shared scope
   * (`trace_level`, a type without a direction, a mixin) or marked `span-kind-neutral` applies in
   * every direction. An unmarked one on a type with a `span-kind` applies only in that type's
   * direction. The map form applies only in the directions it names.
   */
  fun otelMappings(): List<OtelMapping> = buildList {
    fun plain(t: Tag, directions: Collection<Direction>) {
      val otel = t.otelName ?: return
      directions.forEach { add(OtelMapping(t.name, it, otel)) }
    }
    traceLevel.forEach { plain(it, Direction.entries) }
    for (st in spanTypes.toSortedMap().values) {
      val direction = directionOf(st)
      for (t in st.tags) {
        plain(t, if (direction == null || t.spanKindNeutral) Direction.entries else listOf(direction))
      }
    }
    for (mx in mixins.toSortedMap().values) {
      for (t in mx.tags) {
        plain(t, Direction.entries)
        t.otelByDirection.forEach { (direction, otel) -> add(OtelMapping(t.name, direction, otel)) }
      }
    }
  }

  /**
   * Returns the tags whose OpenTelemetry name applies in every direction, mapped to that name.
   * Only these renames are safe to apply without knowing a span's direction.
   */
  fun directionFreeOtelNames(): Map<String, String> = otelMappings()
    .groupBy { it.tag }
    .filterValues { m -> m.size == Direction.entries.size && m.map { it.otelName }.distinct().size == 1 }
    .mapValues { (_, m) -> m.first().otelName }

  private fun directionOf(st: SpanType): Direction? {
    var current: SpanType? = st
    while (current != null) {
      current.spanKind?.let { return SPAN_KIND_DIRECTIONS.getValue(it) }
      current = current.extends?.let { spanTypes[it] }
    }
    return null
  }

  companion object {
    private val SPAN_KIND_DIRECTIONS =
      mapOf(
        "server" to Direction.INBOUND,
        "consumer" to Direction.INBOUND,
        "client" to Direction.OUTBOUND,
        "producer" to Direction.OUTBOUND,
        "internal" to Direction.NONE,
      )

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
          val spanKind = m["span-kind"]
          require(spanKind == null || spanKind in SPAN_KIND_DIRECTIONS) {
            "span type '$name' span-kind must be one of ${SPAN_KIND_DIRECTIONS.keys}"
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
            spanKind = spanKind as String?,
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
          require(m["frame"] == null || m["frame"] == "relative") { "mixin '$name' frame must be 'relative'" }
          Mixin(
            name = name,
            appliesAll = applies == "all",
            appliesTo = if (applies is List<*>) (applies as List<String>).toSet() else emptySet(),
            tags = tagList(m["tags"]),
            refs = refList(m["tags"]),
            frame = m["frame"] as String?,
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
     * Checks where each form of `otel-name` may appear. A plain rename on a concrete span type
     * without a `span-kind` needs `span-kind-neutral: true`, because nothing scopes it to a
     * direction. The map form is allowed only in a `frame: relative` mixin, and
     * `span-kind-neutral` requires a plain rename.
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
      for (t in traceLevel + spanTypes.values.flatMap { it.tags }) {
        require(t.otelByDirection.isEmpty()) {
          "tag '${t.name}' uses a per-direction otel-name outside a `frame: relative` mixin"
        }
      }
      for (mx in mixins.values.filter { it.frame != "relative" }) {
        for (t in mx.tags) {
          require(t.otelByDirection.isEmpty()) {
            "tag '${t.name}' uses a per-direction otel-name in mixin '${mx.name}', which is not " +
              "`frame: relative`"
          }
        }
      }
      fun hasSpanKind(st: SpanType): Boolean {
        var current: SpanType? = st
        while (current != null) {
          if (current.spanKind != null) return true
          current = current.extends?.let { spanTypes[it] }
        }
        return false
      }
      for (st in spanTypes.values.filter { !it.abstract && !hasSpanKind(it) }) {
        for (t in st.tags) {
          require(t.otelName == null || t.spanKindNeutral) {
            "tag '${t.name}' renames to otel-name '${t.otelName}' on concrete span type '${st.name}', " +
              "which has no span-kind. Declare a span-kind to scope the rename to that direction, declare " +
              "it in a shared scope (an abstract parent or a mixin), or, if '${t.otelName}' means " +
              "'${t.name}' on every span kind, add `span-kind-neutral: true`."
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
          otelName = if (m["otel-name"] is Map<*, *>) null else parseOtelName(m),
          spanKindNeutral = parseSpanKindNeutral(m),
          otelByDirection = parseOtelByDirection(m),
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
     * Reads the map form of `otel-name`, `{ outbound: <name>, inbound: <name> }`. Either key may be
     * omitted; each value must be a nonblank string.
     */
    private fun parseOtelByDirection(m: Map<String, Any?>): Map<Direction, String> {
      val raw = m["otel-name"] as? Map<*, *> ?: return emptyMap()
      val byKey = Direction.entries.filter { it != Direction.NONE }.associateBy { it.yamlKey }
      require(raw.isNotEmpty() && raw.keys.all { it in byKey }) {
        "tag '${m["dd-name"]}' per-direction otel-name may only use keys ${byKey.keys}: $raw"
      }
      return raw.entries.associate { (key, value) ->
        require(value is String && value.isNotBlank()) {
          "tag '${m["dd-name"]}' has an invalid $key otel-name: '$value'"
        }
        byKey.getValue(key as String) to value
      }
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
