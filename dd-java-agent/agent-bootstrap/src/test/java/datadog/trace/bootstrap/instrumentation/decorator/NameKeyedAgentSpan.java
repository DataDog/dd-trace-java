package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/**
 * An {@link AgentSpan} to mock in tests whose id-keyed {@code setTag} overloads are {@code final}
 * and delegate to the name-keyed ones. A mock cannot override a final method, so it never sees the
 * id-keyed call: tests keep expecting {@code setTag(name, value)} whether the code under test sets
 * a tag by name or by {@code KnownTags} id.
 */
public abstract class NameKeyedAgentSpan implements AgentSpan {
  @Override
  public final AgentSpan setTag(long tagId, boolean value) {
    return setTag(KnownTagCodec.nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, int value) {
    return setTag(KnownTagCodec.nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, long value) {
    return setTag(KnownTagCodec.nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, float value) {
    return setTag(KnownTagCodec.nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, double value) {
    return setTag(KnownTagCodec.nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, String value) {
    return setTag(KnownTagCodec.nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, CharSequence value) {
    return setTag(KnownTagCodec.nameOf(tagId), value);
  }

  @Override
  public final AgentSpan setTag(long tagId, Object value) {
    return setTag(KnownTagCodec.nameOf(tagId), value);
  }
}
