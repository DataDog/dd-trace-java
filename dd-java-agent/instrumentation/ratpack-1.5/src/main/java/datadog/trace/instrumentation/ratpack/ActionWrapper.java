package datadog.trace.instrumentation.ratpack;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.func.Action;

public class ActionWrapper<T> implements Action<T> {

  private static final Logger log = LoggerFactory.getLogger(ActionWrapper.class);
  private final Action<T> delegate;
  private final AgentSpan span;

  private ActionWrapper(final Action<T> delegate, final AgentSpan span) {
    assert span != null;
    this.delegate = delegate;
    this.span = span;
  }

  @Override
  public void execute(final T t) throws Exception {
    try (final AgentScope scope = activateSpan(span)) {
      delegate.execute(t);
    }
  }

  public static <T> Action<T> wrapIfNeeded(final Action<T> delegate, final AgentSpan span) {
    if (delegate instanceof ActionWrapper || span == null) {
      return delegate;
    }
    log.debug("Wrapping action task {}", delegate);
    return new ActionWrapper(delegate, span);
  }
}
