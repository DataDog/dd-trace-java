package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.TagMap;

/**
 * A baked-once, frozen descriptor of a span's constant initial state — the per-decorator constants
 * (instrumentation name, span type, {@code span.kind}, component, …) that {@code
 * BaseDecorator.afterStart} otherwise stamps one entry at a time, per span.
 *
 * <p>Composed through {@link #builder()}: authors set identity and constant tags via typed methods
 * and never touch {@link TagMap} directly. {@link Builder#extends_(SpanPrototype)} inherits a base
 * prototype (e.g. a SpanType base like {@code HttpServer}) so an integration adds only what's
 * specific to it. Rides the existing {@code TagMap} API, so it's independent of any deeper TagMap
 * rework — the internal seed can get faster without changing this surface.
 *
 * <p>A prototype also carries derived-tag formulas ({@link Builder#initDerivedTag}, {@link
 * Builder#initDerivedTags}): tags its spans compute from other tags when first read, instead of
 * being stamped. Every prototype implicitly extends the tracer's base prototype, whose formulas
 * replace post-processors; a formula here overrides a base formula with the same targets.
 */
public final class SpanPrototype {
  /**
   * The empty prototype, for spans created without a decorator-provided prototype. Such spans use
   * the tracer's base formulas.
   */
  public static final SpanPrototype NONE = builder().build();

  public static Builder builder() {
    return new Builder();
  }

  private final String instrumentationName;
  private final CharSequence operationName;
  private final CharSequence spanType;
  private final CharSequence integrationName;
  private final TagMap tags; // frozen
  private final SpanKind kind;
  private final TagDerivations derivations;

  /** The last {@link #derivationsOver} result; replaced as a unit, so a benign race is safe. */
  private volatile Layered layered;

  private static final class Layered {
    final TagDerivations base;
    final TagDerivations result;

    Layered(TagDerivations base, TagDerivations result) {
      this.base = base;
      this.result = result;
    }
  }

  private SpanPrototype(final Builder builder) {
    this.instrumentationName = builder.instrumentationName;
    this.operationName = builder.operationName;
    this.spanType = builder.spanType;
    this.integrationName = builder.integrationName;
    this.derivations = builder.derivations.build();
    this.tags = builder.tags.immutableCopy();
    // From the final span.kind tag, so every way of setting it is covered: initKind with either
    // type, initTag, or a kind inherited through extends_.
    this.kind = SpanKind.of(tags.getString(Tags.SPAN_KIND));
  }

  public String instrumentationName() {
    return instrumentationName;
  }

  public CharSequence operationName() {
    return operationName;
  }

  public CharSequence spanType() {
    return spanType;
  }

  /**
   * The integration name to record on the span context ({@code setIntegrationName}), which the
   * IntegrationAdder serializer step turns into {@code _dd.integration}. Null unless set via {@link
   * Builder#initComponentAndIntegration}. Mirrors {@code BaseDecorator.afterStart}'s side effect.
   */
  public CharSequence integrationName() {
    return integrationName;
  }

  /** The frozen constant tags — the internal seed applied at span construction. */
  public TagMap tags() {
    return tags;
  }

  /**
   * The {@code span.kind} in {@link #tags()} as a {@link SpanKind}, so the tracer core can set the
   * span's kind without comparing strings. Null if there's no {@code span.kind} or it isn't a
   * standard kind.
   */
  public SpanKind kind() {
    return kind;
  }

  /** This prototype's own formulas, not including the base prototype's. */
  public TagDerivations derivations() {
    return derivations;
  }

  /**
   * This prototype's formulas layered over the tracer's base formulas. The result is remembered for
   * the most recent base, so a span pays for the layering only once per prototype.
   *
   * @param base the tracer's base formulas
   * @return the formulas a span built from this prototype uses
   */
  public TagDerivations derivationsOver(TagDerivations base) {
    if (base.isEmpty()) {
      // Experimental gate: a tracer without base formulas has derived tags switched off, so a
      // prototype's own formulas don't apply either.
      return TagDerivations.EMPTY;
    }
    Layered last = this.layered;
    if (last != null && last.base == base) {
      return last.result;
    }
    TagDerivations result = derivations.over(base);
    this.layered = new Layered(base, result);
    return result;
  }

  public static final class Builder {
    private String instrumentationName;
    private CharSequence operationName;
    private CharSequence spanType;
    private CharSequence integrationName;
    // Internal accumulator — never exposed; authors compose via the typed methods below.
    private final TagMap tags = TagMap.create();
    private final TagDerivations.Builder derivations = TagDerivations.builder();

    private Builder() {}

    /**
     * Inherit a base prototype's identity and constant tags (e.g. a SpanType base). Subsequent
     * identity / {@code init*} calls on this builder override the inherited values.
     */
    public Builder extends_(final SpanPrototype base) {
      if (base != null) {
        if (base.instrumentationName != null) {
          this.instrumentationName = base.instrumentationName;
        }
        if (base.operationName != null) {
          this.operationName = base.operationName;
        }
        if (base.spanType != null) {
          this.spanType = base.spanType;
        }
        if (base.integrationName != null) {
          this.integrationName = base.integrationName;
        }
        this.tags.putAll(base.tags);
        TagDerivations inherited = base.derivations;
        for (int i = 0; i < inherited.size(); ++i) {
          this.derivations.add(inherited.targets(i), inherited.formula(i));
        }
      }
      return this;
    }

    /**
     * Declares a derived tag: computed from other tags when first read, unless the tag is set
     * explicitly. Overrides an inherited formula for the same tag.
     *
     * @param target the {@code KnownTags} id of the tag it produces
     * @param formula a cheap, pure function of the span's tags
     */
    public Builder initDerivedTag(final long target, final TagFormula formula) {
      this.derivations.add(target, formula);
      return this;
    }

    /**
     * Declares derived tags that belong together, computed by one formula, all or nothing: an
     * explicit write to any target suppresses the formula. Overrides an inherited formula with
     * exactly the same targets.
     *
     * @param targets the {@code KnownTags} ids of the tags it produces
     * @param formula a cheap, pure function of the span's tags
     */
    public Builder initDerivedTags(final long[] targets, final TagsFormula formula) {
      this.derivations.add(targets, formula);
      return this;
    }

    public Builder initInstrumentationNames(final String[] instrumentationNames) {
      return (instrumentationNames == null || instrumentationNames.length == 0)
          ? this
          : initInstrumentationName(instrumentationNames[0]);
    }

    public Builder initInstrumentationName(final String instrumentationName) {
      this.instrumentationName = instrumentationName;
      return this;
    }

    public Builder initOperationName(final CharSequence operationName) {
      this.operationName = operationName;
      return this;
    }

    public Builder initSpanType(final CharSequence spanType) {
      this.spanType = spanType;
      return this;
    }

    /** Sets {@code span.kind}. */
    public Builder initKind(final SpanKind kind) {
      return kind == null ? this : initTag(Tags.SPAN_KIND, kind.tagValue());
    }

    /**
     * Sets {@code span.kind} from its tag value. A standard value is recognized as its {@link
     * SpanKind}, the same as {@link #initKind(SpanKind)}; any other value is kept as a custom kind.
     */
    public Builder initKind(final CharSequence kind) {
      return initTag(Tags.SPAN_KIND, kind);
    }

    /**
     * Sets the {@code component} tag only. Does NOT touch the integration name — so overriding a
     * component inherited via {@link #initComponentAndIntegration} with this leaves the inherited
     * integration name in place (a desync). Use {@link #initComponentAndIntegration} to override
     * both together.
     */
    public Builder initComponentOnly(final CharSequence component) {
      return initTag(Tags.COMPONENT, component);
    }

    /**
     * Sets the {@code component} tag AND records it as the integration name — the {@code
     * BaseDecorator.afterStart} pairing (component tag + {@code setIntegrationName(component)},
     * which the IntegrationAdder serializer turns into {@code _dd.integration}). Null/empty is a
     * no-op for both.
     */
    public Builder initComponentAndIntegration(final CharSequence component) {
      if (!TagMap.Entry.isEmptyValue(component)) {
        this.tags.set(Tags.COMPONENT, component);
        this.integrationName = component;
      }
      return this;
    }

    public Builder initTag(final String key, final CharSequence value) {
      if (!TagMap.Entry.isEmptyValue(value)) {
        this.tags.set(key, value);
      }
      return this;
    }

    public Builder initTag(final String key, final Object value) {
      if (!TagMap.Entry.isEmptyValue(value)) {
        this.tags.set(key, value);
      }
      return this;
    }

    /**
     * Advanced/internal: reuse an already-built entry — a decorator's cached constant or a metric
     * entry — rather than re-creating it. Authors should prefer the typed {@code init*} methods.
     */
    public Builder initTag(final TagMap.EntryReader entry) {
      if (entry != null) {
        this.tags.set(entry);
      }
      return this;
    }

    public SpanPrototype build() {
      return new SpanPrototype(this);
    }
  }
}
