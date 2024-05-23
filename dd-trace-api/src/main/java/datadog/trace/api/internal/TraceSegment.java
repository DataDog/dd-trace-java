package datadog.trace.api.internal;

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
  default void setTagTop(String key, Object value) {
    setTagTop(key, value, false);
  }

  /**
   * Add a tag to the top of this {@code TraceSegment} with optional key sanitization.
   *
   * @param key key of the tag
   * @param value value of the tag
   * @param sanitize indicates is key need to be sanitized
   */
  void setTagTop(String key, Object value, boolean sanitize);

  /**
   * Get the tag value from the top of this {@code TraceSegment}.
   *
   * @param key key of the tag
   */
  default Object getTagTop(String key) {
    return getTagTop(key, false);
  }

  /**
   * Get the tag value from the top of this {@code TraceSegment} with optional key sanitization.
   *
   * @param key key of the tag
   * @param sanitize indicates is key need to be sanitized
   */
  Object getTagTop(String key, boolean sanitize);

  /**
   * Add a tag to the current span in this {@code TraceSegment}.
   *
   * @param key key of the tag
   * @param value value of the tag
   */
  default void setTagCurrent(String key, Object value) {
    setTagCurrent(key, value, false);
  }

  /**
   * Add a tag to the current span in this {@code TraceSegment}. with optional key sanitization.
   *
   * @param key key of the tag
   * @param value value of the tag
   * @param sanitize indicates is key need to be sanitized
   */
  void setTagCurrent(String key, Object value, boolean sanitize);

  /**
   * Get the tag value from the current span in this {@code TraceSegment}.
   *
   * @param key key of the tag
   */
  default Object getTagCurrent(String key) {
    return getTagCurrent(key, false);
  }

  /**
   * Get the tag value from the current span in this {@code TraceSegment}. with optional key
   * sanitization.
   *
   * @param key key of the tag
   * @param sanitize indicates is key need to be sanitized
   */
  Object getTagCurrent(String key, boolean sanitize);

  /**
   * Add data to the top of this {@code TraceSegment}. The {@code toString} representation of the
   * {@code value} must be valid top level JSON, i.e. an {@code Object} or an {@code Array}.
   *
   * @param key key of the data
   * @param value value of the data
   */
  void setDataTop(String key, Object value);

  /**
   * Gets the value of the tag from the top of this {@code TraceSegment}.
   *
   * @param key key of the data
   * @return value of the data
   */
  Object getDataTop(String key);

  /** Mark the request as effectively blocked, by setting the tag appsec.blocked */
  void effectivelyBlocked();

  /**
   * Add data to the current span in this {@code TraceSegment}. The {@code toString} representation
   * of the {@code value} must be valid top level JSON, i.e. an {@code Object} or an {@code Array}.
   *
   * @param key key of the data
   * @param value value of the data
   */
  void setDataCurrent(String key, Object value);

  /**
   * Gets the value of the tag from the current span in this {@code TraceSegment}.
   *
   * @param key key of the data
   * @return value of the data
   */
  Object getDataCurrent(String key);

  /**
   * Add a field to the meta_struct of the top of this {@code TraceSegment}.
   *
   * @param field field name
   * @param value value of the data
   * @see #setMetaStructCurrent(String, Object) (String, Object)
   */
  void setMetaStructTop(String field, Object value);

  /**
   * Add a field to the current span meta_struct in this {@code TraceSegment}.
   *
   * @param field field name
   * @param value value of the data
   * @see datadog.trace.common.writer.ddagent.TraceMapperV0_4.MetaStructWriter
   * @see datadog.trace.core.CoreSpan#setMetaStruct(String, Object)
   */
  void setMetaStructCurrent(String field, Object value);

  class NoOp implements TraceSegment {
    public static final TraceSegment INSTANCE = new NoOp();

    private NoOp() {}

    @Override
    public void setTagTop(String key, Object value, boolean sanitize) {}

    @Override
    public void setTagCurrent(String key, Object value, boolean sanitize) {}

    @Override
    public Object getTagTop(String key, boolean sanitize) {
      return null;
    }

    @Override
    public Object getTagCurrent(String key, boolean sanitize) {
      return null;
    }

    @Override
    public void setDataTop(String key, Object value) {}

    @Override
    public Object getDataTop(String key) {
      return null;
    }

    @Override
    public void effectivelyBlocked() {}

    @Override
    public void setDataCurrent(String key, Object value) {}

    @Override
    public Object getDataCurrent(String key) {
      return null;
    }

    @Override
    public void setMetaStructTop(String key, Object value) {}

    @Override
    public void setMetaStructCurrent(String key, Object value) {}
  }
}
