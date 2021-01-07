package datadog.trace.instrumentation.rediscala;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.rediscala.RediscalaClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import scala.runtime.AbstractFunction1;
import scala.util.Try;

public class OnCompleteHandler extends AbstractFunction1<Try<Object>, Void> {
  private final AgentSpan span;

  public OnCompleteHandler(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public Void apply(final Try<Object> result) {
    try {
      if (result.isFailure()) {
        DECORATE.onError(span, result.failed().get());
      }
      DECORATE.beforeFinish(span);
      final TraceScope scope = activeScope();
      if (scope != null) {
        scope.setAsyncPropagation(false);
      }
    } finally {
      span.finish();
    }
    return null;
  }
}
