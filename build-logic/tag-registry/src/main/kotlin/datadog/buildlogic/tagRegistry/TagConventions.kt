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
  /** One tag declaration: its identity, Datadog name, type, requirement level, and optional rename. */
  data class Tag(
    /**
     * The tag's identity. It is the Datadog name, unless that name is declared once per direction,
     * as `peer.port` is: then each declaration is its own tag, named `<dd-name>@<direction>`. That
     * derived name appears in reports; it is not YAML syntax.
     */
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
     * Set `span-kind-neutral: true` to apply a rename declared in a directional scope (a span type
     * or mixin with a `span-kind`) in every direction, not only in that scope's. Set it only when
     * OpenTelemetry uses the name for this tag alone: `db.type` -> `db.system` on `db.client`
     * qualifies, because `db.system` only describes a database.
     *
     * Until name resolution knows a span's direction, only renames that apply in every direction
     * are used, so an unmarked directional rename is recorded but not yet applied. A rename on a
     * concrete span type with no `span-kind` requires the flag; renames in `trace_level`, abstract
     * types, and mixins without a `span-kind` need none. Setting it without a rename is invalid.
     *
     * The flag is interim: it goes away once name resolution is direction-aware.
     */
    val spanKindNeutral: Boolean = false,
    /** The Datadog-namespace name; equal to [name] unless the name is declared per direction. */
    val ddName: String = name,
    /** The direction of a tag declared per direction, or null for every other tag. */
    val sharedNameDirection: Direction? = null,
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
   * [name] is the referenced tag's identity, resolved by direction when the name is declared per
   * direction.
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

  /**
   * A reusable set of tags. A mixin with a `span-kind` is directional: its renames apply only in
   * that direction, and only span types of that direction may receive it.
   */
  data class Mixin(
    val name: String,
    val appliesAll: Boolean,
    val appliesTo: Set<String>,
    val tags: List<Tag>,
    val refs: List<Ref> = emptyList(),
    val spanKind: String? = null,
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
    for (mx in appliedMixins(chain)) {
      mx.tags.forEach { add(it) }
      mx.refs.forEach { if (it.name !in result) add(materialize(it)) }
    }
    return result.values.toList()
  }

  private fun appliedMixins(chain: List<SpanType>): List<Mixin> {
    val chainNames = chain.map { it.name }.toSet()
    return mixins.values.filter { mx -> mx.appliesAll || mx.appliesTo.any { it in chainNames } }
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
   * Returns every OpenTelemetry name per direction. A rename in a scope without a direction
   * (`trace_level`, a span type or mixin without a `span-kind`), or marked `span-kind-neutral`,
   * applies in every direction. An unmarked rename in a directional scope applies only in that
   * scope's direction.
   */
  fun otelMappings(): List<OtelMapping> = buildList {
    fun add(t: Tag, direction: Direction?) {
      val otel = t.otelName ?: return
      val directions = if (direction == null || t.spanKindNeutral) Direction.entries else listOf(direction)
      directions.forEach { add(OtelMapping(t.name, it, otel)) }
    }
    traceLevel.forEach { add(it, null) }
    for (st in spanTypes.toSortedMap().values) {
      val direction = directionOf(spanTypes, st)
      st.tags.forEach { add(it, direction) }
    }
    for (mx in mixins.toSortedMap().values) {
      val direction = mx.spanKind?.let { SPAN_KIND_DIRECTIONS.getValue(it) }
      mx.tags.forEach { add(it, direction) }
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

    /** The type's own or nearest inherited `span-kind` direction, or null when none is declared. */
    private fun directionOf(spanTypes: Map<String, SpanType>, st: SpanType): Direction? = chainOf(spanTypes, st.name).firstNotNullOfOrNull { it.spanKind }?.let { SPAN_KIND_DIRECTIONS.getValue(it) }

    private fun parseSpanKind(m: Map<String, Any?>, owner: String): String? {
      val spanKind = m["span-kind"]
      require(spanKind == null || spanKind in SPAN_KIND_DIRECTIONS) {
        "$owner span-kind must be one of ${SPAN_KIND_DIRECTIONS.keys}"
      }
      return spanKind as String?
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(root: Map<String, Any?>): TagConventions {
      for (section in listOf("span_types", "mixins", "trace_level")) {
        require(root[section] == null || root[section] is Map<*, *>) { "$section must be a mapping" }
      }
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
            spanKind = parseSpanKind(m, "span type '$name'"),
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
            spanKind = parseSpanKind(m, "mixin '$name'"),
          )
        }

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

      validateMixinDirections(parsedSpanTypes, parsedMixins)
      val identities = assignIdentities(parsedSpanTypes, parsedMixins, traceLevel)
      val spanTypes =
        parsedSpanTypes.mapValues { (_, st) ->
          st.copy(
            tags = st.tags.map { identities.rename(it, directionOf(parsedSpanTypes, st)) },
            refs = st.refs.map { identities.resolve(it, "span type '${st.name}'", directionOf(parsedSpanTypes, st)) },
          )
        }
      val mixins =
        parsedMixins.mapValues { (_, mx) ->
          val direction = mx.spanKind?.let { SPAN_KIND_DIRECTIONS.getValue(it) }
          mx.copy(
            tags = mx.tags.map { identities.rename(it, direction) },
            refs = mx.refs.map { identities.resolve(it, "mixin '${mx.name}'", direction) },
          )
        }
      validateOtelNameScope(spanTypes, mixins, traceLevel)
      return TagConventions(spanTypes, mixins, traceLevel)
    }

    /**
     * A directional mixin may reach, through `include` or `applies`, only span types of the same
     * direction; a type with no `span-kind` cannot receive one. So no type receives both sides of a
     * tag declared per direction.
     */
    private fun validateMixinDirections(spanTypes: Map<String, SpanType>, mixins: Map<String, Mixin>) {
      for (st in spanTypes.values.filter { !it.abstract }) {
        val chain = chainOf(spanTypes, st.name)
        val chainNames = chain.map { it.name }.toSet()
        val reaching =
          chain.flatMap { it.include }.mapNotNull { mixins[it] } +
            mixins.values.filter { mx -> mx.appliesAll || mx.appliesTo.any { it in chainNames } }
        val direction = directionOf(spanTypes, st)
        for (mx in reaching.filter { it.spanKind != null }.distinct()) {
          val mixinDirection = SPAN_KIND_DIRECTIONS.getValue(mx.spanKind!!)
          require(direction == mixinDirection) {
            "span type '${st.name}' (${direction?.yamlKey ?: "no span-kind"}) receives mixin '${mx.name}', " +
              "which is ${mixinDirection.yamlKey}"
          }
        }
      }
    }

    /**
     * Assigns tag identities. A `dd-name` is declared once, or once per direction: each declaration
     * in its own directional scope (a span type or mixin with a `span-kind`) becomes its own tag,
     * named `<dd-name>@<direction>`. Any other repeated declaration, including an identical one, is
     * rejected; reuse a shared tag through `{ ref: <dd-name>, required: <level> }`, which may
     * override only `required`.
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
        mx.tags.forEach { declare("mixin ${mx.name}", mx.spanKind?.let { SPAN_KIND_DIRECTIONS.getValue(it) }, it) }
      }

      val perDirection = HashMap<String, Map<Direction, String>>()
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
        perDirection[name] = directions.filterNotNull().associateWith { "$name@${it.yamlKey}" }
      }
      return Identities(byName.keys, perDirection)
    }

    private class Identities(val names: Set<String>, val perDirection: Map<String, Map<Direction, String>>) {
      fun rename(t: Tag, direction: Direction?): Tag {
        val identity = perDirection[t.name]?.getValue(direction!!) ?: return t
        return t.copy(name = identity, ddName = t.name, sharedNameDirection = direction)
      }

      fun resolve(r: Ref, container: String, direction: Direction?): Ref {
        require(r.name in names) { "'$container' refs undeclared tag '${r.name}'" }
        val byDirection = perDirection[r.name] ?: return r
        val identity =
          requireNotNull(direction?.let { byDirection[it] }) {
            "'$container' refs '${r.name}', which is declared per direction, but has no matching " +
              "direction (${direction?.yamlKey ?: "no span-kind"})"
          }
        return r.copy(name = identity)
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
          "`none`, or omit the key entirely for pass-through under the Datadog name. To name each " +
          "direction, declare the tag once per direction in mixins with different span-kinds."
      }
      return raw.takeUnless { it == "none" }
    }
  }
}
