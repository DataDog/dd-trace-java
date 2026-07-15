package datadog.gradle.plugin.tags

/**
 * Parsed tag-conventions domain model + the per-type tag-set resolver. Language-agnostic: it knows
 * only structure (extends / include / applies) and per-tag semantics (name / type / required /
 * source). Id assignment, coloring, and emission are layered on top of the resolved sets.
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

  /** Full stored-tag universe (concrete span types' resolves + trace-level), de-duped by name. */
  fun allStoredTags(): List<Tag> {
    val union = LinkedHashMap<String, Tag>()
    for (type in concreteTypes()) for (t in resolve(type)) union.putIfAbsent(t.name, t)
    for (t in traceLevel) union.putIfAbsent(t.name, t)
    return union.values.toList()
  }

  /** Resolved tag NAMES per concrete type — the co-occurrence structure the colorer needs. */
  fun coOccurrence(): Map<String, Set<String>> =
    concreteTypes().associateWith { resolve(it).map { t -> t.name }.toSet() }

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

      val traceLevel = tagList((root["trace_level"] as? Map<String, Any?>)?.get("tags"))
      return TagConventions(spanTypes, mixins, traceLevel)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tagList(tags: Any?): List<Tag> =
      (tags as? List<Map<String, Any?>>)?.map { m ->
        Tag(
          name = m["tag"].toString(),
          type = (m["type"] as? String) ?: "string",
          required = (m["required"] as? String) ?: "optional",
        )
      } ?: emptyList()
  }
}
