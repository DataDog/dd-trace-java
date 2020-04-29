package datadog.trace.instrumentation.mulehttpconnector.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.mulehttpconnector.client.ClientDecorator.DECORATE;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Pair;
import net.bytebuddy.asm.Advice;

@SuppressWarnings("rawtypes")
public class ClientResponseAdvice {

  @SuppressWarnings("unchecked")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This final AsyncCompletionHandler handler,
      @Advice.Argument(0) final Response response) {
    // TODO instrument AsyncCompletionHandler.onThrowable?
    ContextStore<AsyncCompletionHandler, Pair> contextStore =
        InstrumentationContext.get(AsyncCompletionHandler.class, Pair.class);
    Pair<AgentSpan, AgentSpan> spanWithParent = contextStore.get(handler);
    if (null != spanWithParent) {
      contextStore.put(handler, null);
    }
    if (spanWithParent.hasRight()) {
      DECORATE.onResponse(spanWithParent.getRight(), response);
      DECORATE.beforeFinish(spanWithParent.getRight());
      spanWithParent.getRight().finish();
    }
    return spanWithParent.hasLeft() ? activateSpan(spanWithParent.getLeft()) : null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.Enter final AgentScope scope) {
    if (null != scope) {
      scope.close();
    }
  }
}
