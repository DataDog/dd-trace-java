package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;

/**
 * An {@link AgentSpan} to mock in tests whose id-keyed {@code setTag} overloads are {@code final}
 * and delegate to the name-keyed ones. A mock cannot override a final method, so it never sees the
 * id-keyed call: tests keep expecting {@code setTag(name, value)} whether the code under test sets
 * a tag by name or by {@code KnownTags} id.
 */
public abstract class NameKeyedAgentSpan implements AgentSpan {
  /** The span kind the mock stands for; see {@link #ofKind}. */
  private int kind = KnownTagCodec.KIND_UNSET;

  /**
   * Names a projected concept (server.port) as a span of {@code kind} would. A mock holds no tags,
   * so it can't report its kind itself, and asking would be an interaction tests don't expect.
   */
  public final NameKeyedAgentSpan ofKind(int kind) {
    this.kind = kind;
    return this;
  }

  private String nameOf(long tagId) {
    return KnownTagCodec.hasProjectedName(tagId)
        ? KnownTagCodec.datadogNameOf(tagId, this.kind)
        : KnownTagCodec.nameOf(tagId);
  }

  @Override
  public final <T> AgentSpan setTagsFrom(T source, TagExtractor<T> extractor) {
    return AgentSpan.super.setTagsFrom(source, extractor);
  }

  @Override
  public final AgentSpan setTag(long tagId, boolean value) {
    return setTag(nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, int value) {
    return setTag(nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, long value) {
    return setTag(nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, float value) {
    return setTag(nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, double value) {
    return setTag(nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, String value) {
    return setTag(nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, CharSequence value) {
    return setTag(nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, Object value) {
    return setTag(nameOf(tagId), value);
  }
}
