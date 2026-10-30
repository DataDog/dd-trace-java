package datadog.trace.bootstrap.instrumentation.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.TagMap;
import java.util.ArrayList;
import java.util.List;

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
 * <p>Applying it is equivalent to making the recorded writes again, in order, interception
 * included: it {@linkplain #replay replays} them, or a tracer core copies their net result in at
 * once where it can show that's the same -- no write needs interception and none removes a tag (a
 * null value) or sets an empty one.
 *
 * <p>Tag writes only: an extraction that reads the span ({@code getTag}) or sets span-level state
 * ({@code setResourceName}, {@code setError}, {@code setMeasured}) can't be frozen and throws.
 */
public final class FrozenTags {
  /** No tags. */
  public static final FrozenTags EMPTY = new FrozenTags(TagMap.EMPTY, new Write[0], false);

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

  /** One recorded write: the same setter with the same value. */
  private interface Write {
    void replay(SpanData span);
  }

  /** The net result of the writes, frozen: what a bulk copy copies. */
  private final TagMap tags;

  /** The writes in the order they were made. */
  private final Write[] writes;

  /** Some write removed a tag or set an empty value, so only a replay is equivalent. */
  private final boolean replayOnly;

  /**
   * The tracer core's plan for applying these tags, built on first use. Racy by design, like {@link
   * SpanPrototype#seedPlan()}: the plan is immutable, so a reader sees null or a complete plan.
   */
  private Object applyPlan;

  private FrozenTags(final TagMap tags, final Write[] writes, final boolean replayOnly) {
    this.tags = tags;
    this.writes = writes;
    this.replayOnly = replayOnly;
  }

  /** The net result of the recorded writes, frozen. */
  public TagMap tags() {
    return tags;
  }

  /** Whether nothing was written. */
  public boolean isEmpty() {
    return writes.length == 0;
  }

  /**
   * Whether only {@link #replay} is equivalent to the writes: some write removed a tag or set an
   * empty value, which copying {@link #tags} in wouldn't do.
   */
  public boolean isReplayOnly() {
    return replayOnly;
  }

  /** Makes the recorded writes on {@code span}, in order. */
  public void replay(final SpanData span) {
    for (Write write : writes) {
      write.replay(span);
    }
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
    private final List<Write> writes = new ArrayList<>();
    private boolean replayOnly;

    FrozenTags freeze() {
      return writes.isEmpty()
          ? EMPTY
          : new FrozenTags(tags.freeze(), writes.toArray(new Write[0]), replayOnly);
    }

    /** Notes a write whose value a bulk copy of the net result wouldn't reproduce. */
    private void check(final Object value) {
      if (TagMap.Entry.isEmptyValue(value)) {
        replayOnly = true; // null removes the tag; an empty value is stored
      }
    }

    @Override
    public SpanData setTag(String key, Object value) {
      check(value);
      tags.set(key, value);
      writes.add(span -> span.setTag(key, value));
      return this;
    }

    @Override
    public SpanData setTag(String key, String value) {
      check(value);
      tags.set(key, (CharSequence) value);
      writes.add(span -> span.setTag(key, value));
      return this;
    }

    @Override
    public SpanData setTag(String key, boolean value) {
      tags.set(key, value);
      writes.add(span -> span.setTag(key, value));
      return this;
    }

    @Override
    public SpanData setTag(String key, int value) {
      tags.set(key, value);
      writes.add(span -> span.setTag(key, value));
      return this;
    }

    @Override
    public SpanData setTag(String key, long value) {
      tags.set(key, value);
      writes.add(span -> span.setTag(key, value));
      return this;
    }

    @Override
    public SpanData setTag(String key, float value) {
      tags.set(key, value);
      writes.add(span -> span.setTag(key, value));
      return this;
    }

    @Override
    public SpanData setTag(String key, double value) {
      tags.set(key, value);
      writes.add(span -> span.setTag(key, value));
      return this;
    }

    @Override
    public SpanData setTag(long tagId, Object value) {
      check(value);
      tags.set(tagId, value);
      writes.add(span -> span.setTag(tagId, value));
      return this;
    }

    @Override
    public SpanData setTag(long tagId, CharSequence value) {
      check(value);
      tags.set(tagId, value);
      writes.add(span -> span.setTag(tagId, value));
      return this;
    }

    @Override
    public SpanData setTag(long tagId, boolean value) {
      tags.set(tagId, value);
      writes.add(span -> span.setTag(tagId, value));
      return this;
    }

    @Override
    public SpanData setTag(long tagId, int value) {
      tags.set(tagId, value);
      writes.add(span -> span.setTag(tagId, value));
      return this;
    }

    @Override
    public SpanData setTag(long tagId, long value) {
      tags.set(tagId, value);
      writes.add(span -> span.setTag(tagId, value));
      return this;
    }

    @Override
    public SpanData setTag(long tagId, float value) {
      tags.set(tagId, value);
      writes.add(span -> span.setTag(tagId, value));
      return this;
    }

    @Override
    public SpanData setTag(long tagId, double value) {
      tags.set(tagId, value);
      writes.add(span -> span.setTag(tagId, value));
      return this;
    }

    @Override
    public SpanData setTag(TagMap.EntryReader entry) {
      if (entry == null) {
        return this; // no change, as on a span
      }
      // the reader may be a reused flyweight: keep an entry of its own
      TagMap.Entry copy = entry.entry();
      tags.set(copy);
      writes.add(span -> span.setTag(copy));
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
