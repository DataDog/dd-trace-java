package datadog.trace.instrumentation.rediscala;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.rediscala.RediscalaClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import scala.runtime.AbstractFunction1;
import scala.util.Try;

public final class OnCompleteHandler extends AbstractFunction1<Try<Object>, Void> {

  public static final OnCompleteHandler INSTANCE = new OnCompleteHandler();

  @Override
  public Void apply(final Try<Object> result) {
    // propagation handled by scala promise instrumentation
    AgentScope activeScope = activeScope();
    if (null != activeScope) {
      AgentSpan span = activeScope.span();
      try {
        if (result.isFailure()) {
          DECORATE.onError(span, result.failed().get());
        }
        DECORATE.beforeFinish(span);
      } finally {
        span.finish();
      }
    }
    return null;
  }
}
