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
  /** Datadog names the tracer intercepts on the set-path, from the tracer overlay. */
  val interceptedNames: Set<String> = emptySet(),
) {
  /**
   * A tag's identity: its Datadog name, plus the direction when that name is declared once per
   * direction, as `peer.port` is. Two declarations of a name in different directions are two tags.
   * A value, not a string, so no declared name can be mistaken for a derived identity.
   */
  data class TagIdentity(val ddName: String, val direction: Direction? = null) {
    /**
     * How reports and generated constant names show the identity: the Datadog name, or
     * `<dd-name>@<direction>` for a name declared per direction. Display only, not YAML syntax.
     */
    val label: String
      get() = if (direction == null) ddName else "$ddName@${direction.yamlKey}"

    override fun toString() = label
  }

  /** One tag declaration: its identity, type, requirement level, and optional rename. */
  data class Tag(
    val identity: TagIdentity,
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
     * Applies a rename in every direction. Set `span-kind-neutral: true` only when both names
     * denote the same value on every span kind, such as `db.type` and `db.system`.
     *
     * A span type or mixin with `span-kind` otherwise scopes its renames to that direction.
     * This includes `internal` (spans with no direction). Scoped renames are recorded but kept
     * out of the generated lookup tables until resolution supports directions. This flag
     * will be removed then.
     *
     * Renames in scopes without a direction already apply everywhere. A concrete type with no
     * declared or inherited `span-kind` must still set this flag for a rename.
     *
     * The flag requires an `otel-name` and cannot be used on a tag declared per direction.
     */
    val spanKindNeutral: Boolean = false,
  ) {
    /** The identity's label, as reports show it; see [TagIdentity.label]. */
    val name: String
      get() = identity.label

    /** The Datadog-namespace name, shared by the tags of a name declared per direction. */
    val ddName: String
      get() = identity.ddName

    /** The direction of a tag declared per direction, or null for every other tag. */
    val sharedNameDirection: Direction?
      get() = identity.direction
  }

  /**
   * Span direction, derived from `span-kind`: `server` and `consumer` are inbound, `client` and
   * `producer` are outbound, and `internal` has no direction.
   */
  enum class Direction(val yamlKey: String) {
    INBOUND("inbound"),
    OUTBOUND("outbound"),
    NONE("none"),
  }

  /** The OpenTelemetry name for [tag] on spans of [direction], or of every direction when null. */
  data class OtelMapping(val tag: TagIdentity, val otelName: String, val direction: Direction?)

  /**
   * A `{ ref: <dd-name>, required: <level> }` entry. It reuses a tag declared elsewhere, optionally
   * at a different requirement level; its type and otel-name always come from that declaration.
   * [name] is the referenced tag's identity, resolved by direction when the name is declared per
   * direction.
   */
  data class Ref(val identity: TagIdentity, val required: String?)

  data class SpanType(
    val name: String,
    val abstract: Boolean,
    val extends: String?,
    val include: List<String>,
    val tags: List<Tag>,
    val refs: List<Ref> = emptyList(),
    /** The direction set by this type's own `span-kind`, or null; see [directionOf] for inheritance. */
    val direction: Direction? = null,
  )

  /**
   * A reusable tag set. When `span-kind` is present, receiving concrete span types must have
   * the same declared or inherited direction. Renames are scoped to that direction unless
   * `span-kind-neutral` widens them to every direction.
   */
  data class Mixin(
    val name: String,
    val appliesAll: Boolean,
    val appliesTo: Set<String>,
    val tags: List<Tag>,
    val refs: List<Ref> = emptyList(),
    /** The direction set by this mixin's `span-kind`, or null for a mixin of any direction. */
    val direction: Direction? = null,
  )

  private val declarations: Map<TagIdentity, Tag> by lazy {
    allDeclaredTags().associateBy { it.identity }
  }

  /** The tag a [Ref] names, at the ref's requirement level when it overrides one. */
  private fun materialize(ref: Ref): Tag {
    val decl = declarations.getValue(ref.identity)
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
    val result = LinkedHashMap<TagIdentity, Tag>()
    fun add(t: Tag) = result.putIfAbsent(t.identity, t)
    fun applyRef(r: Ref) {
      val current = result[r.identity]
      // Re-putting an existing key keeps its LinkedHashMap position.
      result[r.identity] =
        when {
          current == null -> materialize(r)
          r.required == null -> current
          else -> current.copy(required = r.required)
        }
    }

    val chain = chainOf(spanTypes, typeName)
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
    for (mx in appliedMixins(mixins, chain)) {
      mx.tags.forEach { add(it) }
      mx.refs.forEach { if (it.identity !in result) add(materialize(it)) }
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
  }.distinctBy { it.identity }

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
   * Returns each tag's OpenTelemetry name and the direction it applies in. A rename in a scope
   * without a direction (`trace_level`, a span type or mixin without a `span-kind`), or marked
   * `span-kind-neutral`, applies in every direction (a null direction). An unmarked rename in a
   * directional scope applies only in that scope's direction.
   */
  fun otelMappings(): List<OtelMapping> = buildList {
    fun add(t: Tag, direction: Direction?) {
      val otel = t.otelName ?: return
      add(OtelMapping(t.identity, otel, direction.takeUnless { t.spanKindNeutral }))
    }
    traceLevel.forEach { add(it, null) }
    for (st in spanTypes.toSortedMap().values) {
      val direction = directionOf(spanTypes, st)
      st.tags.forEach { add(it, direction) }
    }
    for (mx in mixins.toSortedMap().values) {
      mx.tags.forEach { add(it, mx.direction) }
    }
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

    private fun chainOf(spanTypes: Map<String, SpanType>, typeName: String): List<SpanType> {
      val chain = ArrayList<SpanType>()
      var current: SpanType? = spanTypes[typeName]
      while (current != null) {
        chain.add(current)
        current = current.extends?.let { spanTypes[it] }
      }
      return chain
    }

    /** The mixins whose `applies` matches a type in [chain]. */
    private fun appliedMixins(mixins: Map<String, Mixin>, chain: List<SpanType>): List<Mixin> {
      val chainNames = chain.map { it.name }.toSet()
      return mixins.values.filter { mx -> mx.appliesAll || mx.appliesTo.any { it in chainNames } }
    }

    /** The type's own or nearest inherited `span-kind` direction, or null when none is declared. */
    private fun directionOf(spanTypes: Map<String, SpanType>, st: SpanType): Direction? = chainOf(spanTypes, st.name).firstNotNullOfOrNull { it.direction }

    /** Reads `span-kind` as the direction it sets, or null when absent. */
    private fun overlayMixin(overlay: Map<String, Any?>): Map<String, Mixin> {
      val tags = tagList(overlay["tags"])
      if (tags.isEmpty()) return emptyMap()
      return mapOf(
        OVERLAY_MIXIN to
          Mixin(name = OVERLAY_MIXIN, appliesAll = false, appliesTo = emptySet(), tags = tags, refs = emptyList())
      )
    }

    private fun parseDirection(m: Map<String, Any?>, owner: String): Direction? {
      val spanKind = m["span-kind"]
      require(spanKind == null || spanKind in SPAN_KIND_DIRECTIONS) {
        "$owner span-kind must be one of ${SPAN_KIND_DIRECTIONS.keys}"
      }
      return (spanKind as String?)?.let { SPAN_KIND_DIRECTIONS.getValue(it) }
    }

    @Suppress("UNCHECKED_CAST")
    /** The synthetic mixin holding the tracer overlay's tags; it applies to no span type. */
    const val OVERLAY_MIXIN = "tracer overlay"

    /**
     * Parses [root], the language-agnostic conventions, plus [overlay], this tracer's own set-path
     * routing: `tags` declares keys that exist only to be routed (e.g. `resource.name`), and
     * `intercepted` lists the Datadog names the tracer intercepts, from either file.
     */
    fun parse(root: Map<String, Any?>, overlay: Map<String, Any?> = emptyMap()): TagConventions {
      for (section in listOf("span_types", "mixins", "trace_level")) {
        require(root[section] == null || root[section] is Map<*, *>) { "$section must be a mapping" }
      }
      require(overlay.keys.all { it == "tags" || it == "intercepted" }) {
        "the tracer overlay may only declare `tags` and `intercepted`, not ${overlay.keys - setOf("tags", "intercepted")}"
      }
      require(refList(overlay["tags"]).isEmpty()) { "tracer overlay tags must be declarations, not refs" }
      val interceptedRaw = overlay["intercepted"]
      require(interceptedRaw == null || (interceptedRaw is List<*> && interceptedRaw.all { it is String })) {
        "intercepted must be a list of Datadog tag names"
      }
      val intercepted = (interceptedRaw as? List<String>)?.toSet() ?: emptySet()
      val spanTypesRaw = (root["span_types"] as? Map<String, Any?>) ?: emptyMap()
      val parsedSpanTypes =
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
            direction = parseDirection(m, "span type '$name'"),
          )
        }

      val mixinsRaw = (root["mixins"] as? Map<String, Any?>) ?: emptyMap()
      val parsedMixins =
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
            direction = parseDirection(m, "mixin '$name'"),
          )
        } + overlayMixin(overlay)

      for (spanType in parsedSpanTypes.values) {
        for (included in spanType.include) {
          require(included in parsedMixins) {
            "span type '${spanType.name}' includes unknown mixin '$included'"
          }
        }
        val visited = HashSet<String>()
        var current: SpanType? = spanType
        while (current != null) {
          val name = current.name
          require(visited.add(name)) { "span type '${spanType.name}' has cyclic extends at '$name'" }
          current = current.extends?.let { parent ->
            requireNotNull(parsedSpanTypes[parent]) { "span type '$name' extends unknown span type '$parent'" }
          }
        }
      }

      // Trace-level tags pass through under their Datadog name for now; their OTel mapping (resource
      // attributes) is a follow-on. TODO(otel follow-on).
      val traceLevelRaw = (root["trace_level"] as? Map<String, Any?>)?.get("tags")
      val traceLevel = tagList(traceLevelRaw)
      require(refList(traceLevelRaw).isEmpty()) { "trace_level tags must be declarations, not refs" }

      validateInheritedDirections(parsedSpanTypes)
      validateMixinDirections(parsedSpanTypes, parsedMixins)
      val identities = assignIdentities(parsedSpanTypes, parsedMixins, traceLevel)
      val spanTypes =
        parsedSpanTypes.mapValues { (_, st) ->
          val direction = directionOf(parsedSpanTypes, st)
          st.copy(
            tags = st.tags.map { identities.rename(it, direction) },
            refs = st.refs.map { identities.resolve(it, "span type '${st.name}'", direction) },
          )
        }
      val mixins =
        parsedMixins.mapValues { (_, mx) ->
          mx.copy(
            tags = mx.tags.map { identities.rename(it, mx.direction) },
            refs = mx.refs.map { identities.resolve(it, "mixin '${mx.name}'", mx.direction) },
          )
        }
      validateOtelNameScope(spanTypes, mixins, traceLevel)
      val conv = TagConventions(spanTypes, mixins, traceLevel, intercepted)
      val declared = conv.allDeclaredTags().map { it.ddName }.toSet()
      for (name in intercepted) {
        require(name in declared) { "intercepted tag '$name' is not declared" }
      }
      return conv
    }

    /**
     * Rejects a type whose declared direction differs from its nearest directional ancestor.
     * Ancestor tags retain their direction, so changing it could give a type both identities
     * of a Datadog name declared per direction.
     */
    private fun validateInheritedDirections(spanTypes: Map<String, SpanType>) {
      for (st in spanTypes.values) {
        val direction = st.direction ?: continue
        val ancestor = st.extends?.let { chainOf(spanTypes, it) }?.firstOrNull { it.direction != null } ?: continue
        require(direction == ancestor.direction) {
          "span type '${st.name}' (${direction.yamlKey}) changes the direction it inherits from " +
            "'${ancestor.name}' (${ancestor.direction!!.yamlKey})"
        }
      }
    }

    /**
     * Restricts directional mixins to concrete types with the same declared or inherited
     * direction. Checks inherited `include` entries and `applies` targeting any ancestor.
     * This prevents mixins from contributing both directions of a shared Datadog name.
     */
    private fun validateMixinDirections(spanTypes: Map<String, SpanType>, mixins: Map<String, Mixin>) {
      for (st in spanTypes.values.filter { !it.abstract }) {
        val chain = chainOf(spanTypes, st.name)
        val reaching = chain.flatMap { it.include }.mapNotNull { mixins[it] } + appliedMixins(mixins, chain)
        val direction = directionOf(spanTypes, st)
        for (mx in reaching.distinct()) {
          val mixinDirection = mx.direction ?: continue
          require(direction == mixinDirection) {
            "span type '${st.name}' (${direction?.yamlKey ?: "no span-kind"}) receives mixin '${mx.name}', " +
              "which is ${mixinDirection.yamlKey}"
          }
        }
      }
    }

    /**
     * Uses `dd-name` as the identity for a single declaration. A name declared in multiple
     * directions gets `<dd-name>@<direction>` for each declaration; each must have a distinct
     * declared or inherited direction.
     *
     * All other duplicate declarations fail, including identical ones. Reuse a tag with
     * `{ ref: <dd-name>, required: <level> }`; a reference may override only `required`.
     */
    private fun assignIdentities(
      spanTypes: Map<String, SpanType>,
      mixins: Map<String, Mixin>,
      traceLevel: List<Tag>,
    ): Identities {
      data class Declaration(val container: String, val direction: Direction?)
      val byName = LinkedHashMap<String, MutableList<Declaration>>()
      fun declare(container: String, direction: Direction?, t: Tag) {
        byName.getOrPut(t.name) { ArrayList() }.add(Declaration(container, direction))
      }
      traceLevel.forEach { declare("<trace>", null, it) }
      spanTypes.values.forEach { st -> st.tags.forEach { declare(st.name, directionOf(spanTypes, st), it) } }
      mixins.values.forEach { mx ->
        mx.tags.forEach { declare("mixin ${mx.name}", mx.direction, it) }
      }

      val perDirection = HashMap<String, Set<Direction>>()
      for ((name, decls) in byName) {
        if (decls.size == 1) continue
        val first = decls[0]
        val second = decls[1]
        val directions = decls.map { it.direction }
        require(directions.none { it == null } && directions.distinct().size == directions.size) {
          "tag '$name' is declared in both '${first.container}' and '${second.container}'. Declare it " +
            "once and use `{ ref: $name, required: <level> }` elsewhere (a ref may override only " +
            "`required`), or declare it once per direction in scopes with different span-kinds."
        }
        perDirection[name] = directions.filterNotNull().toSet()
      }
      return Identities(byName.keys, perDirection)
    }

    private class Identities(val names: Set<String>, val perDirection: Map<String, Set<Direction>>) {
      fun rename(t: Tag, direction: Direction?): Tag {
        if (t.ddName !in perDirection) return t
        // Declaring a tag per direction says its meaning flips with direction; neutral says it doesn't.
        require(!t.spanKindNeutral) {
          "tag '${t.name}' is declared per direction, so its otel-name '${t.otelName}' cannot be " +
            "span-kind-neutral; each direction's declaration names its own"
        }
        return t.copy(identity = TagIdentity(t.ddName, direction))
      }

      fun resolve(r: Ref, container: String, direction: Direction?): Ref {
        val name = r.identity.ddName
        require(name in names) { "'$container' refs undeclared tag '$name'" }
        val directions = perDirection[name] ?: return r
        require(direction != null && direction in directions) {
          "'$container' refs '$name', which is declared per direction, but has no matching " +
            "direction (${direction?.yamlKey ?: "no span-kind"})"
        }
        return r.copy(identity = TagIdentity(name, direction))
      }
    }

    /**
     * Checks where `span-kind-neutral` is required and where it is allowed. A rename on a concrete
     * span type without a `span-kind` requires it, because nothing scopes that rename to a
     * direction. The flag itself requires a rename to apply to.
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
      for (st in spanTypes.values.filter { !it.abstract && directionOf(spanTypes, it) == null }) {
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
        Ref(TagIdentity(name), m["required"] as? String)
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
          identity = TagIdentity(name),
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
          "`none`, or omit the key entirely for pass-through under the Datadog name. To name each " +
          "direction, declare the tag once per direction in mixins with different span-kinds."
      }
      return raw.takeUnless { it == "none" }
    }
  }
}
