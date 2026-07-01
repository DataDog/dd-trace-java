package datadog.trace.api;

/**
 * A baked-once, frozen descriptor of a span's constant initial tags — the per-decorator constants
 * that are otherwise stamped one entry at a time in {@code BaseDecorator.afterStart}.
 *
 * <p>Provided by {@code BaseDecorator.prototype()} (composed across the decorator hierarchy and
 * cached), and applied to a span via {@code setAllTags} — later, seeded into the span's {@link
 * TagMap} at construction. Because it rides the existing {@code TagMap} API, it is independent of
 * any deeper {@code TagMap} rework: the internal seed can get faster without changing this surface.
 *
 * <p>v1 carries only the constant tag set. Constant span fields ({@code spanType}, integration
 * name, …) and later facets (derivation, canonicalization, lifecycle hooks) are deliberately out of
 * scope — the concept earns its extensibility by being simple and well-placed, not by pre-built
 * slots.
 */
public final class SpanPrototype {
  /** The empty prototype — for spans created without a decorator-provided prototype. */
  public static final SpanPrototype NONE = new SpanPrototype(TagMap.create(0).immutableCopy());

  private final TagMap tags;

  private SpanPrototype(final TagMap frozenTags) {
    this.tags = frozenTags;
  }

  /**
   * Bakes a prototype from the given constant tags. The tags are frozen (an immutable copy is
   * taken), so the caller may reuse the source map.
   */
  public static SpanPrototype of(final TagMap tags) {
    return new SpanPrototype(tags.immutableCopy());
  }

  /** The frozen constant tags this prototype stamps onto a span. */
  public TagMap tags() {
    return tags;
  }
}
