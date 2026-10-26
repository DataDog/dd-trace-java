package datadog.trace.api;

/**
 * A span's tags as stored, for the tracer core's processing pipeline: loads and stores go straight
 * to storage, with no interception. Thread safety is up to the implementation; {@code
 * DDSpanContext}'s expects the span's tag lock to be held.
 *
 * <p>Its own verbs, {@code load} and {@code store}, keep it apart from {@link TagMap}'s {@code
 * get}/{@code set} and the span's intercepted {@code getTag}/{@code setTag}, so a type can offer
 * several of these without a clash.
 *
 * <p>A known tag can be addressed by its {@link KnownTags} id or by its name, and both address the
 * same tag. Prefer the id: it skips classifying the name on every call. A custom tag has no id.
 *
 * <p>This is the first slice: only what the eager tag post-processors need. Typed loads and
 * primitive stores come as more of the pipeline moves to it.
 */
public interface SpanStorage {
  /** The tag's value, or null when it's absent. */
  Object load(String tag);

  /** The tag's value as a string, or null when it's absent. */
  String loadString(String tag);

  /** The known tag's value, or null when it's absent. */
  Object load(long tagId);

  /** The known tag's value as a string, or null when it's absent. */
  String loadString(long tagId);

  /** Stores the tag; a null value removes it. */
  void store(String tag, Object value);

  /** Stores the known tag; a null value removes it. */
  void store(long tagId, Object value);

  /** Stores the entry's tag; a null entry is a no-op. */
  void store(TagMap.EntryReader entry);
}
