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
 * <p>v1 carries identity + constant tags. Derivation / canonicalization / lifecycle hooks are
 * deliberately out — grown when the work that needs each arrives, not pre-slotted.
 */
public final class SpanPrototype {
  /** The empty prototype — for spans created without a decorator-provided prototype. */
  public static final SpanPrototype NONE = builder().build();

  public static Builder builder() {
    return new Builder();
  }

  private final String instrumentationName;
  private final CharSequence operationName;
  private final CharSequence spanType;
  private final TagMap tags; // frozen

  private SpanPrototype(final Builder builder) {
    this.instrumentationName = builder.instrumentationName;
    this.operationName = builder.operationName;
    this.spanType = builder.spanType;
    this.tags = builder.tags.immutableCopy();
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

  /** The frozen constant tags — the internal seed applied at span construction. */
  public TagMap tags() {
    return tags;
  }

  public static final class Builder {
    private String instrumentationName;
    private CharSequence operationName;
    private CharSequence spanType;
    // Internal accumulator — never exposed; authors compose via the typed methods below.
    private final TagMap tags = TagMap.create();

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
        this.tags.putAll(base.tags);
      }
      return this;
    }

    public Builder instrumentationName(final String[] instrumentationNames) {
      return (instrumentationNames == null || instrumentationNames.length == 0)
          ? this
          : instrumentationName(instrumentationNames[0]);
    }

    public Builder instrumentationName(final String instrumentationName) {
      this.instrumentationName = instrumentationName;
      return this;
    }

    public Builder operationName(final CharSequence operationName) {
      this.operationName = operationName;
      return this;
    }

    public Builder spanType(final CharSequence spanType) {
      this.spanType = spanType;
      return this;
    }

    /** Sets {@code span.kind}. */
    public Builder initKind(final CharSequence kind) {
      return initTag(Tags.SPAN_KIND, kind);
    }

    /** Sets {@code component}. */
    public Builder initComponent(final CharSequence component) {
      return initTag(Tags.COMPONENT, component);
    }

    public Builder initTag(final String key, final CharSequence value) {
      if (value != null) {
        this.tags.set(key, value);
      }
      return this;
    }

    public Builder initTag(final String key, final Object value) {
      if (value != null) {
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
