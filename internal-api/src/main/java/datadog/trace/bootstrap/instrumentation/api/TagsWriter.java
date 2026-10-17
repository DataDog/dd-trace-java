package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.function.ScopeBound;

/**
 * Where a {@link TagContributor} or {@link TagExtractor} writes its tags. {@link
 * AgentSpan#setTagsFrom} hands one over for the length of that call.
 *
 * <p>A span's writer takes no lock per write: {@code setTagsFrom} holds the span's tag lock across
 * the whole contributor or extractor, so a sweep of N writes costs one acquisition, where N {@code
 * span.setTag} calls cost N unless the JIT happens to coarsen them. Writes otherwise behave as
 * {@code AgentSpan.setTag} does, interception included: a tag that sets span-level state ({@code
 * resource.name}, {@code error}, ...) still sets it.
 *
 * <p>A writer is {@link ScopeBound}: use it only inside the call that received it, on that call's
 * thread. Don't store it, capture it in a lambda, or hand it to another thread: outside that call,
 * nothing holds the lock it relies on. Under strict trace writes, as in tests, a writer used after
 * its call returns or from another thread throws.
 */
@ScopeBound
public interface TagsWriter {
  void setTag(long tagId, boolean value);

  void setTag(long tagId, int value);

  void setTag(long tagId, long value);

  void setTag(long tagId, float value);

  void setTag(long tagId, double value);

  void setTag(long tagId, CharSequence value);

  void setTag(long tagId, Object value);

  void setTag(String tag, boolean value);

  void setTag(String tag, int value);

  void setTag(String tag, long value);

  void setTag(String tag, float value);

  void setTag(String tag, double value);

  void setTag(String tag, CharSequence value);

  void setTag(String tag, Object value);
}
