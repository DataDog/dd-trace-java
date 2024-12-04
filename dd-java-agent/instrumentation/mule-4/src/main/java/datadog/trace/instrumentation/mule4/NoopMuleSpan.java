package datadog.trace.instrumentation.mule4;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.mule.runtime.api.profiling.tracing.Span;
import org.mule.runtime.api.profiling.tracing.SpanDuration;
import org.mule.runtime.api.profiling.tracing.SpanError;
import org.mule.runtime.api.profiling.tracing.SpanIdentifier;

public class NoopMuleSpan implements Span {
  public static final Optional<Span> INSTANCE = Optional.of(new NoopMuleSpan());

  @Override
  public Span getParent() {
    return null;
  }

  @Override
  public SpanIdentifier getIdentifier() {
    return SpanIdentifier.INVALID_SPAN_IDENTIFIER;
  }

  @Override
  public String getName() {
    return "";
  }

  @Override
  public SpanDuration getDuration() {
    return null;
  }

  @Override
  public List<SpanError> getErrors() {
    return Collections.emptyList();
  }

  @Override
  public boolean hasErrors() {
    return false;
  }
}
