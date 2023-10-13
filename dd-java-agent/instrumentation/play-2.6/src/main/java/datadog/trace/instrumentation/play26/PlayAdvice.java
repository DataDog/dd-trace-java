package datadog.trace.instrumentation.play26;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.play26.PlayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.play26.PlayHttpServerDecorator.PLAY_REQUEST;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import net.bytebuddy.asm.Advice;
import play.api.mvc.Action;
import play.api.mvc.Headers;
import play.api.mvc.Request;
import play.api.mvc.Result;
import scala.concurrent.Future;

public class PlayAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(value = 0, readOnly = false) Request req) {
    final AgentSpan span;

    // If we have already added a `play.request` span, then don't do it again
    if (req.attrs().contains(HasPlayRequestSpan.KEY)) {
      return null;
    }

    if (activeSpan() == null) {
      final Headers headers = req.headers();
      final Context.Extracted extractedContext = DECORATE.extract(headers);
      span = DECORATE.startSpan(headers, extractedContext);
    } else {
      // An upstream framework (e.g. akka-http, netty) has already started the span.
      // Do not extract the context.
      span = startSpan(PLAY_REQUEST);
    }
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    span.setMeasured(true);
    DECORATE.afterStart(span);

    req = req.addAttr(HasPlayRequestSpan.KEY, HasPlayRequestSpan.INSTANCE);

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopTraceOnResponse(
      @Advice.Enter final AgentScope playControllerScope,
      @Advice.This final Object thisAction,
      @Advice.Thrown final Throwable throwable,
      @Advice.Argument(0) final Request req,
      @Advice.Return(readOnly = false) final Future<Result> responseFuture) {

    if (playControllerScope == null) {
      return;
    }

    final AgentSpan playControllerSpan = playControllerScope.span();

    // Call onRequest on return after tags are populated.
    DECORATE.onRequest(playControllerSpan, req, req, null);

    if (throwable == null) {
      responseFuture.onComplete(
          new RequestCompleteCallback(playControllerSpan),
          ((Action) thisAction).executionContext());
    } else {
      DECORATE.onError(playControllerSpan, throwable);
      DECORATE.beforeFinish(playControllerSpan);
      playControllerSpan.finish();
    }
    playControllerScope.close();
    // span finished in RequestCompleteCallback

    final AgentSpan rootSpan = activeSpan();
    // set the resource name on the upstream akka/netty span if there is one
    if (rootSpan != null) {
      DECORATE.onRequest(rootSpan, req, req, null);
    }
  }
}
