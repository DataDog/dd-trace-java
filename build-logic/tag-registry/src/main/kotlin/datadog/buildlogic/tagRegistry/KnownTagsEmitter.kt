package datadog.buildlogic.tagRegistry

import com.fasterxml.jackson.core.io.JsonStringEncoder
import java.util.Locale

/**
 * Emits the generated `KnownTags.java` from a [TagRegistry]. Public API first — per-tag
 * `<X>_NAME` (string) + `<X>_ID` (encoded long, literal) couplets with a trailing `// makeTagId(...)`
 * derivation comment — then the package-private `<X>_SERIAL_NUM` constants, the
 * `StringIndex.EmbeddingSupport` keyOf table and the resolver's name switches.
 */
object KnownTagsEmitter {

  fun emit(reg: TagRegistry, pkg: String, className: String): String {
    // Sanitize a tag name into a Java identifier base (not yet unique).
    fun sanitize(name: String): String {
      var c = name.uppercase().replace(Regex("[^A-Za-z0-9]"), "_").replace(Regex("_+"), "_").trim('_')
      if (c.isEmpty() || c[0].isDigit()) c = "T_$c"
      return c
    }

    // Collapse a duplicated trailing token so e.g. "resource.name" yields NAME (not NAME_NAME) and
    // "_dd.parent_id" yields ID (not ID_ID); the non-duplicating pairs (ID + _NAME -> ID_NAME, NAME
    // + _ID -> NAME_ID) are kept as-is.
    fun withSuffix(base: String, suffix: String) = if (base.endsWith(suffix)) base else "$base$suffix"

    // NAME/ID/SERIAL_NUM constants are all fields of the same generated class, so uniqueness must be
    // enforced on these FINAL suffixed identifiers, not on the pre-suffix base: two different base
    // names can collapse to the same final identifier once a suffix is appended -- e.g. base
    // "RESOURCE" suffixed with "_NAME" collides with a base that is already "RESOURCE_NAME" (which
    // withSuffix leaves untouched, since it already ends with "_NAME").
    val used = HashSet<String>()
    fun unique(name: String): String {
      var u = name
      var n = 2
      while (!used.add(u)) {
        u = "${name}_${n++}"
      }
      return u
    }

    val nameOfConst = HashMap<String, String>()
    val idOfConst = HashMap<String, String>()
    val serialOfConst = HashMap<String, String>()
    val otelNameOfConst = HashMap<String, String>()
    for (t in reg.tags) {
      val base = sanitize(t.name)
      // A Datadog name declared per direction is shared by a tag per direction, so on its own it
      // names no single tag. It gets no NAME constant: callers use the unambiguous per-direction ID
      // (PEER_PORT_OUTBOUND_ID), and nameOf returns the shared name as a literal.
      if (t.sharedNameDirection == null) nameOfConst[t.name] = unique(withSuffix(base, "_NAME"))
      idOfConst[t.name] = unique(withSuffix(base, "_ID"))
      serialOfConst[t.name] = unique(withSuffix(base, "_SERIAL_NUM"))
      // Suffix the pre-suffix base (not nameC), same as the other three: suffixing an
      // already-suffixed identifier would produce a redundant compound like NAME_OTEL_NAME.
      if (t.otelName != null) otelNameOfConst[t.name] = unique(withSuffix(base, "_OTEL_NAME"))
    }
    fun nameC(name: String) = nameOfConst.getValue(name)

    // The expression nameOf returns: the NAME constant, or the shared name's literal.
    fun nameExpr(t: TagRegistry.Tag) = nameOfConst[t.name] ?: "\"${escape(t.ddName)}\""
    fun idC(name: String) = idOfConst.getValue(name)
    fun serialC(name: String) = serialOfConst.getValue(name)
    fun otelNameC(name: String) = otelNameOfConst.getValue(name)

    val order = reg.tags.map { it.name } // stable emit order
    // A shared name cannot pick one of its tags without the span's direction, so it is left out of
    // keyOf and resolves to no tag until resolution knows the direction.
    val keyOfOrder = reg.tags.filter { it.sharedNameDirection == null }.map { it.name }
    // canonical name -> OpenTelemetry name, for the reverse (openTelemetryNameOf) switch.
    val otelName = reg.tags.mapNotNull { t -> t.otelName?.let { t.name to it } }.toMap()
    // Names whose tag depends on the span's direction: a shared Datadog name (one tag per direction)
    // and a direction-scoped OpenTelemetry name. keyOf(name) resolves none of them; keyOf(name,
    // direction) switches on each one's tag per direction.
    val directional = sortedMapOf<String, MutableMap<TagConventions.Direction, String>>()
    // Shared Datadog names, marked in the keyOf table so a lookup by that name alone can probe its tags.
    val sharedNames = sortedSetOf<String>()
    for (t in reg.tags) {
      t.sharedNameDirection?.let {
        directional.getOrPut(t.ddName) { mutableMapOf() }[it] = t.name
        sharedNames.add(t.ddName)
      }
      t.otelDirection?.let { directional.getOrPut(t.declaredOtelName!!) { mutableMapOf() }[it] = t.name }
    }
    check(directional.keys.none { it in keyOfOrder || it in otelName.values }) {
      "a direction-dependent name is also direction-free: ${directional.keys}"
    }
    return buildString {
      // Public API first (name + encoded id couplets), so readers see the useful parts up top; the
      // serial ids and keyOf/resolver machinery follow below. Derivation is in the trailing comment.
      appendLine(
        """
        package $pkg;

        import datadog.trace.util.StringIndex;

        // GENERATED by the tag-registry code generator (dd-trace-java.tag-registry-generator).
        // DO NOT EDIT. Source: tag-conventions.yaml.
        public final class $className {

          // ---- tags ----
        """.trimIndent()
      )

      for (t in reg.tags) {
        val direction = t.sharedNameDirection
        if (direction == null) {
          appendLine("  public static final String ${nameC(t.name)} = \"${escape(t.ddName)}\";")
        } else {
          appendLine(
            "  /** {@code ${escape(t.ddName)}} on ${direction.yamlKey} spans. That name alone is shared by a " +
              "tag per direction. */"
          )
        }
        appendLine("  public static final long ${idC(t.name)} = ${hex(t.id)};")
        if (t.otelName != null) {
          appendLine("  public static final String ${otelNameC(t.name)} = \"${escape(t.otelName)}\";")
        }
        append("// makeTagId(serial=${t.serial})")
        if (t.traceLevel) append(" + trace-level")
        if (t.otelName != null) append(" -> ${escape(t.otelName)}")
        appendLine("  <${escape(t.required)}>")
        appendLine()
      }

      // Serial numbers (globalSerial per tag) — package-private, consumed by the resolver switch.
      appendLine("  // ---- serial numbers ----")
      for (t in reg.tags) {
        appendLine("  static final int ${serialC(t.name)} = ${t.serial};")
      }

      // OpenTelemetry name -> canonical tag name. Validation ensures aliases are distinct from all
      // canonical names. Sort by OTel name to keep output deterministic.
      val otelByCanonical =
        reg.tags
          .mapNotNull { t -> t.otelName?.let { it to t.name } }
          .sortedBy { it.first }

      // keyOf table (open-addressed, via StringIndex.EmbeddingSupport). Canonical names first, then
      // OpenTelemetry names -- an OTel name resolves to its canonical tag's id (there is no distinct id
      // for it), so keyOf(otelName) == keyOf(canonical); nameOf still returns the canonical name.
      appendLine(
        """
        
          private static final String[] KEYOF_NAMES = {
        """.trimIndent()
      )
      keyOfOrder.forEach { appendLine("    ${nameC(it)},") }
      otelByCanonical.forEach { (otel, _) ->
        appendLine("    \"${escape(otel)}\",")
      }
      directional.keys.forEach { appendLine("    \"${escape(it)}\",") }
      appendLine(
        """
          };
          private static final long[] KEYOF_VALUES = {
        """.trimIndent()
      )
      keyOfOrder.forEach { appendLine("    ${idC(it)},") }
      otelByCanonical.forEach { (_, canonical) ->
        appendLine("    ${idC(canonical)},")
      }
      // A direction-dependent name's value is not an id but a marker saying which kind it is.
      for (name in directional.keys) {
        val marker = if (name in sharedNames) "SHARED_NAME" else "DIRECTION_SCOPED_NAME"
        appendLine("    KnownTagCodec.$marker, // $name")
      }
      // Resolver. KnownTagCodec.Installed links to this field directly, so merely resolving a tag
      // name initializes this class -- there is no registration step and no ordering to get wrong.
      appendLine(
        """
            };
          private static final int[] KEYOF_HASHES;
          private static final String[] KEYOF_KEYS;
          private static final long[] KEYOF_IDS;

          static {
            StringIndex.Data data = StringIndex.EmbeddingSupport.create(KEYOF_NAMES);
            long[] ids = new long[data.names.length];
            for (int j = 0; j < KEYOF_NAMES.length; j++) {
              ids[StringIndex.EmbeddingSupport.indexOf(data.hashes, data.names, KEYOF_NAMES[j])] =
                  KEYOF_VALUES[j];
            }
            KEYOF_HASHES = data.hashes;
            KEYOF_KEYS = data.names;
            KEYOF_IDS = ids;
          }

          /**
           * The registry's name&harr;id tables, as a {@link KnownTagCodec.Resolver}. {@code KnownTagCodec}
           * reads this field from its own holder, so the two classes complete each other: the codec owns
           * the bit layout and the naming policy, this class owns the data. Nothing has to be called first.
           */
          static final KnownTagCodec.Resolver RESOLVER =
              new KnownTagCodec.Resolver() {
                @Override
                public String nameOf(long tagId) {
                  switch (KnownTagCodec.serialNum(tagId)) {
        """.trimIndent()
      )
      for (t in reg.tags) {
        appendLine(
          """
                      case ${serialC(t.name)}:
                        return ${nameExpr(t)};
          """.trimIndent()
        )
      }
      // openTelemetryNameOf: id -> OTel-namespace name on spans of a direction, null when the tag has
      // none there. The caller (a serializer) owns any fall-back-to-Datadog-name policy.
      appendLine(
        """
                    default:
                      return null;
                  }
                }

              @Override
              public String openTelemetryNameOf(long tagId, int direction) {
                switch (KnownTagCodec.serialNum(tagId)) {
        """.trimIndent()
      )
      for (t in reg.tags) {
        val otel = t.declaredOtelName ?: continue
        val scoped = t.otelDirection
        val result =
          when {
            scoped == null -> otelNameC(t.name)

            // A tag declared per direction exists only on spans of its direction, so emitting it
            // needs none; only resolving the name to it does.
            t.sharedNameDirection != null -> "\"${escape(otel)}\""

            else -> "direction == KnownTagCodec.DIRECTION_${scoped.name} ? \"${escape(otel)}\" : null"
          }
        appendLine("case ${serialC(t.name)}:")
        appendLine("  return $result;")
      }
      appendLine(
        """
                  default:
                    return null;
                }
              }

              @Override
              public long lookup(String name) {
                int slot = StringIndex.EmbeddingSupport.indexOf(KEYOF_HASHES, KEYOF_KEYS, name);
                return slot < 0 ? 0L : KEYOF_IDS[slot];
              }

              @Override
              public long directionalKeyOf(String name, int direction) {
                switch (name) {
        """.trimIndent()
      )
      for ((name, byDirection) in directional) {
        appendLine("    case \"${escape(name)}\":")
        appendLine("      switch (direction) {")
        for (d in TagConventions.Direction.entries) {
          val tag = byDirection[d] ?: continue
          appendLine("        case KnownTagCodec.DIRECTION_${d.name}:")
          appendLine("          return ${idC(tag)};")
        }
        appendLine("        default:")
        appendLine("          return KnownTagCodec.NO_TAG_IN_DIRECTION;")
        appendLine("      }")
      }
      appendLine(
        """
                  default:
                    return 0L;
                }
              }
            };

        private $className() {}
      }
        """.trimIndent()
      )
    }
  }

  private fun hex(id: Long): String = "0x%016XL".format(Locale.ROOT, id)

  private fun escape(value: String): String = String(JsonStringEncoder.getInstance().quoteAsString(value))
}
