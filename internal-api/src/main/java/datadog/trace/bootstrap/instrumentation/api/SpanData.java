package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.TagMap;

/**
 * What a {@link TagContributor} or {@link TagExtractor} may read and set on a span: its tags, with
 * the span's full tag semantics (a null value removes the tag, and the tag interceptor runs), and
 * the few span-level fields that aren't yet tags.
 *
 * <p>The intercepted counterpart of {@link datadog.trace.api.SpanStorage}, which loads and stores
 * raw. It uses the span's own vocabulary, {@code getTag}/{@code setTag}, so contributor code reads
 * like span code. Unlike OpenTelemetry's {@code SpanData}, it is not a snapshot: writes go to the
 * live span.
 *
 * <p>{@link AgentSpan#setTagsFrom} may hand its contributor a short-lived {@code SpanData} that
 * holds the span's tag lock for the whole call, so a run of {@code setTag} calls takes the lock
 * once. Such an instance is valid only during that call: don't keep it.
 */
public interface SpanData {
  SpanData setTag(String key, Object value);

  SpanData setTag(String key, String value);

  SpanData setTag(String key, boolean value);

  SpanData setTag(String key, int value);

  SpanData setTag(String key, long value);

  SpanData setTag(String key, float value);

  SpanData setTag(String key, double value);

  /** Sets a known tag by its {@code KnownTags.*_ID}; an id that names no known tag is ignored. */
  SpanData setTag(long tagId, Object value);

  SpanData setTag(long tagId, CharSequence value);

  SpanData setTag(long tagId, boolean value);

  SpanData setTag(long tagId, int value);

  SpanData setTag(long tagId, long value);

  SpanData setTag(long tagId, float value);

  SpanData setTag(long tagId, double value);

  /** A null entry leaves the tags unchanged. */
  SpanData setTag(TagMap.EntryReader entry);

  Object getTag(String key);

  SpanData setResourceName(CharSequence resourceName);

  SpanData setError(boolean error);

  SpanData setMeasured(boolean measured);
}
