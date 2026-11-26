package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nullable;

/**
 * What a derived-tag formula reads: the span's known tags by id, and a few span fields. Every read
 * is recorded as a dependency of the formula, including reads of tags that are absent, so a later
 * write to any of them invalidates the formula's outputs.
 *
 * <p>Valid only during the formula call it is passed to.
 */
public interface TagReader {
  /**
   * The value of a known tag, which may itself be derived.
   *
   * @param tagId a {@code KnownTags} id
   * @return the value, or null if the tag is absent
   */
  @Nullable
  Object get(long tagId);

  /**
   * {@link #get} as a String.
   *
   * @param tagId a {@code KnownTags} id
   * @return the value's string form, or null if the tag is absent
   */
  @Nullable
  String getString(long tagId);

  /**
   * Whether a known tag is present.
   *
   * @param tagId a {@code KnownTags} id
   * @return true if {@link #get} would return a value
   */
  boolean isSet(long tagId);

  /** The span's service name. Recorded as a field dependency. */
  @Nullable
  String serviceName();

  /** The span's integration name. Recorded as a field dependency. */
  @Nullable
  CharSequence integrationName();

  /** Why the span has its service name. Recorded as a field dependency. */
  @Nullable
  CharSequence serviceNameSource();
}
