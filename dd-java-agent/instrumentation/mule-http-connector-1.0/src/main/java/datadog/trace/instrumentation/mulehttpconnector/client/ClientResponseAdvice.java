package datadog.trace.instrumentation.mulehttpconnector.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.mulehttpconnector.client.ClientDecorator.DECORATE;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.ParentChildSpan;
import net.bytebuddy.asm.Advice;

@SuppressWarnings("rawtypes")
public class ClientResponseAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This final AsyncCompletionHandler handler,
      @Advice.Argument(0) final Response response) {
    // TODO instrument AsyncCompletionHandler.onThrowable?
    ContextStore<AsyncCompletionHandler, ParentChildSpan> contextStore =
        InstrumentationContext.get(AsyncCompletionHandler.class, ParentChildSpan.class);
    ParentChildSpan spanWithParent = contextStore.get(handler);
    if (null != spanWithParent) {
      contextStore.put(handler, null);
    }
    if (spanWithParent.hasChild()) {
      DECORATE.onResponse(spanWithParent.getChild(), response);
      DECORATE.beforeFinish(spanWithParent.getChild());
      spanWithParent.getChild().finish();
    }
    return spanWithParent.hasParent() ? activateSpan(spanWithParent.getParent()) : null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(@Advice.Enter final AgentScope scope) {
    if (null != scope) {
      scope.close();
    }
  }
}
