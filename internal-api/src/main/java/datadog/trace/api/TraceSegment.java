package datadog.trace.api;

/**
 * A {@code TraceSegment} represents the local, i.e. in the scope of this {@code Tracer}, part of a
 * a {@code Trace}. It can consist of multiple spans, and the {@code TraceSegment} instance can only
 * be used in relation to the {@code Span} from which it was acquired. This means that the {@code
 * TraceSegment} should not be stored and used at a later time, or passed around to async functions.
 */
public interface TraceSegment {

  /**
   * Add a tag to the top of this {@code TraceSegment}.
   *
   * @param key key of the tag
   * @param value value of the tag
   */
  void setTagTop(String key, Object value);

  /**
   * Add a tag to the current span in this {@code TraceSegment}.
   *
   * @param key key of the tag
   * @param value value of the tag
   */
  void setTagCurrent(String key, Object value);

  /**
   * Add data to the top of this {@code TraceSegment}. The {@code toString} representation of the
   * {@code value} must be valid top level JSON, i.e. an {@code Object} or an {@code Array}.
   *
   * @param key key of the data
   * @param value value of the data
   */
  void setDataTop(String key, Object value);

  /**
   * Add data to the current span in this {@code TraceSegment}. The {@code toString} representation
   * of the {@code value} must be valid top level JSON, i.e. an {@code Object} or an {@code Array}.
   *
   * @param key key of the data
   * @param value value of the data
   */
  void setDataCurrent(String key, Object value);

  class NoOp implements TraceSegment {
    public static final TraceSegment INSTANCE = new NoOp();

    private NoOp() {}

    @Override
    public void setTagTop(String key, Object value) {}

    @Override
    public void setTagCurrent(String key, Object value) {}

    @Override
    public void setDataTop(String key, Object value) {}

    @Override
    public void setDataCurrent(String key, Object value) {}
  }
}
