package datadog.trace.core;

import datadog.trace.bootstrap.instrumentation.api.TagsWriter;

/**
 * Enforces {@link datadog.trace.api.function.ScopeBound} for a {@link TagsWriter} at run time:
 * {@link DDSpanContext#setTagsFrom} hands one out under strict trace writes, as in tests. A write
 * after the {@code setTagsFrom} call returned, or from a thread other than the one that called it,
 * throws, so a contributor or extractor that stores or captures its writer fails its tests instead
 * of racing in production.
 */
final class ScopeCheckedTagsWriter implements TagsWriter {
  private final TagsWriter delegate;
  private final Thread owner = Thread.currentThread();

  /** Cleared when the scope ends; volatile, so a late write on another thread sees it too. */
  private volatile boolean open = true;

  ScopeCheckedTagsWriter(TagsWriter delegate) {
    this.delegate = delegate;
  }

  /** Ends the scope: every write after this throws. */
  void close() {
    open = false;
  }

  private void check() {
    if (!open) {
      throw new IllegalStateException(
          "TagsWriter used after the setTagsFrom call that handed it out returned;"
              + " it is @ScopeBound: don't store or capture it");
    }
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException(
          "TagsWriter used from "
              + Thread.currentThread().getName()
              + " while its setTagsFrom call runs on "
              + owner.getName()
              + "; it is @ScopeBound: don't hand it to another thread");
    }
  }

  @Override
  public void setTag(long tagId, boolean value) {
    check();
    delegate.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, int value) {
    check();
    delegate.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, long value) {
    check();
    delegate.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, float value) {
    check();
    delegate.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, double value) {
    check();
    delegate.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, CharSequence value) {
    check();
    delegate.setTag(tagId, value);
  }

  @Override
  public void setTag(long tagId, Object value) {
    check();
    delegate.setTag(tagId, value);
  }

  @Override
  public void setTag(String tag, boolean value) {
    check();
    delegate.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, int value) {
    check();
    delegate.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, long value) {
    check();
    delegate.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, float value) {
    check();
    delegate.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, double value) {
    check();
    delegate.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, CharSequence value) {
    check();
    delegate.setTag(tag, value);
  }

  @Override
  public void setTag(String tag, Object value) {
    check();
    delegate.setTag(tag, value);
  }
}
