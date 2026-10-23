package datadog.trace.api;

/**
 * Read access to a span's contents. The contents may still change through other references.
 *
 * @see SpanContents
 */
public interface ReadableSpanContents {
  /** The tag's value, or null when it's absent. */
  Object getObject(String tag);

  /** The tag's value as a string, or null when it's absent. */
  String getString(String tag);
}
