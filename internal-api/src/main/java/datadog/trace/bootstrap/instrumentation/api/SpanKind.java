package datadog.trace.bootstrap.instrumentation.api;

/**
 * The standard {@code span.kind} values, typed. It's a closed set, so the tracer core can keep a
 * span's kind as a small ordinal instead of comparing strings; pass it to {@link
 * SpanPrototype.Builder#initKind(SpanKind)}.
 *
 * <p>Other kinds (e.g. the test-visibility ones) stay strings: {@link #of} returns null for them.
 */
public enum SpanKind {
  SERVER(Tags.SPAN_KIND_SERVER),
  CLIENT(Tags.SPAN_KIND_CLIENT),
  PRODUCER(Tags.SPAN_KIND_PRODUCER),
  CONSUMER(Tags.SPAN_KIND_CONSUMER),
  INTERNAL(Tags.SPAN_KIND_INTERNAL),
  BROKER(Tags.SPAN_KIND_BROKER);

  private static final SpanKind[] VALUES = values();

  private final String tagValue;

  SpanKind(final String tagValue) {
    this.tagValue = tagValue;
  }

  /** The {@code span.kind} tag value. */
  public String tagValue() {
    return tagValue;
  }

  /** The kind whose tag value is {@code value}, or null if it isn't a standard kind. */
  public static SpanKind of(final CharSequence value) {
    if (value == null) {
      return null;
    }
    for (SpanKind kind : VALUES) {
      if (kind.tagValue.contentEquals(value)) {
        return kind;
      }
    }
    return null;
  }
}
