package datadog.trace.bootstrap.instrumentation.api;

/**
 * A {@link TagsWriter} that forwards each write to a span's own {@code setTag}: the default for
 * {@link AgentSpan#setTagsFrom}, for a span with no batched writer of its own. Each write locks, or
 * not, as that span's {@code setTag} does.
 */
final class SpanTagsWriter implements TagsWriter {
  private final AgentSpan span;

  SpanTagsWriter(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void setTag(long tagId, boolean value) {
    span.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, int value) {
    span.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, long value) {
    span.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, float value) {
    span.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, double value) {
    span.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, CharSequence value) {
    span.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, Object value) {
    span.setTag(tagId, value);
  }

  @Override
  public void setTag(String tag, boolean value) {
    span.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, int value) {
    span.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, long value) {
    span.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, float value) {
    span.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, double value) {
    span.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, CharSequence value) {
    span.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, Object value) {
    span.setTag(tag, value);
  }
}
