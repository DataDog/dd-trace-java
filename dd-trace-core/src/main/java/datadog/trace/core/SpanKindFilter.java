package datadog.trace.core;

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
