package datadog.trace.bootstrap.instrumentation.api;

/**
 * Where a multiple-output formula ({@link TagsFormula}) puts its results. A formula may set only
 * the targets it declared, and may leave any of them unset.
 *
 * <p>Valid only during the formula call it is passed to.
 */
public interface TagWriter {
  /**
   * Sets one of the formula's declared targets.
   *
   * @param tagId a declared target's {@code KnownTags} id
   * @param value the value; null leaves the target unset
   * @throws IllegalStateException if {@code tagId} is not one of the formula's declared targets
   */
  void set(long tagId, Object value);
}
