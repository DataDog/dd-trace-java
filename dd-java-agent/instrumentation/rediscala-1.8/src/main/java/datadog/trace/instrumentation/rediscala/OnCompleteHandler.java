package datadog.trace.instrumentation.rediscala;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.rediscala.RediscalaClientDecorator.DECORATE;

import akka.actor.ActorRef;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import scala.runtime.AbstractFunction1;
import scala.util.Try;

public final class OnCompleteHandler extends AbstractFunction1<Try<Object>, Void> {

  private final ContextStore<ActorRef, RedisConnectionInfo> contextStore;
  private final ActorRef actorRef;

  public OnCompleteHandler(
      ContextStore<ActorRef, RedisConnectionInfo> contextStore, ActorRef actorRef) {
    this.contextStore = contextStore;
    this.actorRef = actorRef;
  }

  @Override
  public Void apply(final Try<Object> result) {
    // propagation handled by scala promise instrumentation
    AgentSpan span = activeSpan();
    if (null != span) {
      try {
        if (actorRef != null) {
          DECORATE.onConnection(span, contextStore.get(actorRef));
        }
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
