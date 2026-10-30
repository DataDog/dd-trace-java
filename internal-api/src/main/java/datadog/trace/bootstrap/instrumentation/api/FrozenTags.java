package datadog.trace.bootstrap.instrumentation.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap;

/**
 * The tags a {@link TagExtractor} or {@link TagContributor} writes, recorded once and frozen, so a
 * span can take them with {@link AgentSpan#setTagsFrom(FrozenTags)} instead of rerunning the
 * extraction: the cached, cheaper form of {@code setTagsFrom}.
 *
 * <p>Use it when the source outlives the spans that take its tags and the tags are a function of it
 * alone -- a remote address, a connection, a client -- and especially when reading the source
 * builds new objects (an IP string, a joined list). Build it once per source with {@link
 * #of(Object, TagExtractor)} and keep it wherever the source's other per-object state lives; it is
 * immutable, so any thread can apply it.
 *
 * <p>Applying it is equivalent to the recorded writes, interception included. A tracer core may
 * copy the tags in at once when it can show none of them needs interception or is already present.
 *
 * <p>Tag writes only: an extraction that reads the span ({@code getTag}) or sets span-level state
 * ({@code setResourceName}, {@code setError}, {@code setMeasured}) can't be frozen and throws. A
 * null value is recorded as no write, so unlike a live {@code setTag(tag, null)} it doesn't remove
 * a tag the span already has.
 */
public final class FrozenTags {
  /** No tags. */
  public static final FrozenTags EMPTY = new FrozenTags(TagMap.EMPTY);

  /** Records the tags {@code extractor} writes for {@code source}. */
  public static <T> FrozenTags of(final T source, final TagExtractor<T> extractor) {
    if (source == null) {
      return EMPTY;
    }
    Recorder recorder = new Recorder();
    extractor.extract(source, recorder);
    return recorder.freeze();
  }

  /** Records the tags {@code contributor} writes. */
  public static FrozenTags of(final TagContributor contributor) {
    if (contributor == null) {
      return EMPTY;
    }
    Recorder recorder = new Recorder();
    contributor.addTo(recorder);
    return recorder.freeze();
  }

  private final TagMap tags; // frozen

  /**
   * The tracer core's plan for applying these tags, built on first use. Racy by design, like {@link
   * SpanPrototype#seedPlan()}: the plan is immutable, so a reader sees null or a complete plan.
   */
  private Object applyPlan;

  private FrozenTags(final TagMap tags) {
    this.tags = tags;
  }

  /** The recorded tags, frozen. */
  public TagMap tags() {
    return tags;
  }

  public boolean isEmpty() {
    return tags.isEmpty();
  }

  /** The tracer core's apply plan for these tags, or null before the first span uses them. */
  @Restricted(allowedIn = {"datadog.trace.core"})
  public Object applyPlan() {
    return applyPlan;
  }

  /** Records the tracer core's apply plan; see {@link #applyPlan()}. */
  @Restricted(allowedIn = {"datadog.trace.core"})
  public void applyPlan(final Object plan) {
    this.applyPlan = plan;
  }

  /** The {@link SpanData} an extraction writes to while it is recorded. */
  private static final class Recorder implements SpanData {
    private final TagMap tags = TagMap.create();

    FrozenTags freeze() {
      return tags.isEmpty() ? EMPTY : new FrozenTags(tags.freeze());
    }

    @Override
    public SpanData setTag(String key, Object value) {
      tags.set(key, value);
      return this;
    }

    @Override
    public SpanData setTag(String key, String value) {
      tags.set(key, (CharSequence) value);
      return this;
    }

    @Override
    public SpanData setTag(String key, boolean value) {
      tags.set(key, value);
      return this;
    }

    @Override
    public SpanData setTag(String key, int value) {
      tags.set(key, value);
      return this;
    }

    @Override
    public SpanData setTag(String key, long value) {
      tags.set(key, value);
      return this;
    }

    @Override
    public SpanData setTag(String key, float value) {
      tags.set(key, value);
      return this;
    }

    @Override
    public SpanData setTag(String key, double value) {
      tags.set(key, value);
      return this;
    }

    @Override
    public SpanData setTag(long tagId, Object value) {
      tags.set(tagId, value);
      return this;
    }

    @Override
    public SpanData setTag(long tagId, CharSequence value) {
      tags.set(tagId, value);
      return this;
    }

    @Override
    public SpanData setTag(long tagId, boolean value) {
      tags.set(tagId, value);
      return this;
    }

    @Override
    public SpanData setTag(long tagId, int value) {
      tags.set(tagId, value);
      return this;
    }

    @Override
    public SpanData setTag(long tagId, long value) {
      tags.set(tagId, value);
      return this;
    }

    @Override
    public SpanData setTag(long tagId, float value) {
      tags.set(tagId, value);
      return this;
    }

    @Override
    public SpanData setTag(long tagId, double value) {
      tags.set(tagId, value);
      return this;
    }

    @Override
    public SpanData setTag(TagMap.EntryReader entry) {
      tags.set(entry);
      return this;
    }

    @Override
    public Object getTag(String key) {
      // the result would depend on the span the tags are later applied to
      throw new UnsupportedOperationException(
          "FrozenTags records writes only; an extraction that reads the span can't be frozen");
    }

    @Override
    public SpanData setResourceName(CharSequence resourceName) {
      throw spanLevelState("setResourceName");
    }

    @Override
    public SpanData setError(boolean error) {
      throw spanLevelState("setError");
    }

    @Override
    public SpanData setMeasured(boolean measured) {
      throw spanLevelState("setMeasured");
    }

    private static UnsupportedOperationException spanLevelState(String method) {
      return new UnsupportedOperationException(
          "FrozenTags records tags only; an extraction that calls " + method + " can't be frozen");
    }
  }
}
