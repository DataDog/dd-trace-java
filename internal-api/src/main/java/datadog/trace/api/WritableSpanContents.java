package datadog.trace.api;

/**
 * Write access to a span's contents, for code that only sets tags.
 *
 * @see SpanContents
 */
public interface WritableSpanContents {
  /** Sets the tag. */
  void set(String tag, Object value);

  /** Sets the entry's tag; a null entry is a no-op. */
  void set(TagMap.EntryReader entry);
}
