package datadog.trace.core;

/**
 * Bitmask-based eligibility test over the six recognized {@code span.kind} values (server, client,
 * producer, consumer, internal, broker). A filter is built once via {@link #builder()} and then
 * applied per span by either matching a cached kind ordinal (fast path on {@link DDSpan}) or
 * looking up the {@code span.kind} tag (default path on {@link CoreSpan#isKind}).
 *
 * <p>Arbitrary {@code span.kind} strings outside the six recognized values collapse to {@link
 * DDSpanContext#SPAN_KIND_CUSTOM} and never match — by design. Callers that need custom-string
 * matching should read the tag directly via {@link CoreSpan#unsafeGetTag} instead.
 */
public final class SpanKindFilter {
  public static final class Builder {
    private int kindMask;

    public Builder includeServer() {
      return this.include(DDSpanContext.SPAN_KIND_SERVER);
    }

    public Builder includeClient() {
      return this.include(DDSpanContext.SPAN_KIND_CLIENT);
    }

    public Builder includeProducer() {
      return this.include(DDSpanContext.SPAN_KIND_PRODUCER);
    }

    public Builder includeConsumer() {
      return this.include(DDSpanContext.SPAN_KIND_CONSUMER);
    }

    public Builder includeInternal() {
      return this.include(DDSpanContext.SPAN_KIND_INTERNAL);
    }

    public Builder includeBroker() {
      return this.include(DDSpanContext.SPAN_KIND_BROKER);
    }

    public final SpanKindFilter build() {
      return new SpanKindFilter(this.kindMask);
    }

    private Builder include(int spanKindConstant) {
      this.kindMask |= (1 << spanKindConstant);
      return this;
    }
  }

  public static final Builder builder() {
    return new Builder();
  }

  private final int kindMask;

  private SpanKindFilter(int kindMask) {
    this.kindMask = kindMask;
  }

  /** Test whether a span with the given span.kind string passes this filter. */
  public boolean matches(String spanKind) {
    return matches(DDSpanContext.spanKindOrdinalOf(spanKind));
  }

  /** Fast-path test for callers that already hold the span's cached kind ordinal. */
  public boolean matches(byte spanKindOrdinal) {
    return (kindMask & (1 << spanKindOrdinal)) != 0;
  }
}
