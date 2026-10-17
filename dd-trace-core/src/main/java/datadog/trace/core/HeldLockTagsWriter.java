package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;

import datadog.trace.bootstrap.instrumentation.api.TagsWriter;

/**
 * The {@link TagsWriter} {@link DDSpanContext#setTagsFrom} hands a contributor or extractor while
 * it holds the span's tag lock: each write routes and stores as {@code DDSpan.setTag} would,
 * without taking the lock again. Created per {@code setTagsFrom} call; when the contributor
 * inlines, escape analysis removes it.
 *
 * <p>Mirrors {@link DDSpan}'s setters, not only the context's: an empty {@code CharSequence}
 * removes the tag, and an {@code int} {@code http.status_code} set by name also sets the span's
 * status code.
 */
final class HeldLockTagsWriter implements TagsWriter {
  private final DDSpanContext context;

  HeldLockTagsWriter(DDSpanContext context) {
    this.context = context;
  }

  @Override
  public void setTag(long tagId, boolean value) {
    context.setTagHoldingLock(tagId, value);
  }

  @Override
  public void setTag(long tagId, int value) {
    context.setTagHoldingLock(tagId, value);
  }

  @Override
  public void setTag(long tagId, long value) {
    context.setTagHoldingLock(tagId, value);
  }

  @Override
  public void setTag(long tagId, float value) {
    context.setTagHoldingLock(tagId, value);
  }

  @Override
  public void setTag(long tagId, double value) {
    context.setTagHoldingLock(tagId, value);
  }

  @Override
  public void setTag(long tagId, CharSequence value) {
    context.setTagHoldingLock(tagId, value == null || value.length() == 0 ? null : value);
  }

  @Override
  public void setTag(long tagId, Object value) {
    context.setTagHoldingLock(tagId, value);
  }

  @Override
  public void setTag(String tag, boolean value) {
    context.setTagHoldingLock(tag, value);
  }

  @Override
  public void setTag(String tag, int value) {
    if (HTTP_STATUS.equals(tag)) {
      context.setHttpStatusCode((short) value);
    }
    context.setTagHoldingLock(tag, value);
  }

  @Override
  public void setTag(String tag, long value) {
    context.setTagHoldingLock(tag, value);
  }

  @Override
  public void setTag(String tag, float value) {
    context.setTagHoldingLock(tag, value);
  }

  @Override
  public void setTag(String tag, double value) {
    context.setTagHoldingLock(tag, value);
  }

  @Override
  public void setTag(String tag, CharSequence value) {
    context.setTagHoldingLock(tag, value == null || value.length() == 0 ? null : value);
  }

  @Override
  public void setTag(String tag, Object value) {
    context.setTagHoldingLock(tag, value);
  }
}
