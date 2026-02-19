package datadog.trace.instrumentation.play23;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getRootContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.play23.PlayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.play23.PlayHttpServerDecorator.PLAY_REQUEST;
import static datadog.trace.instrumentation.play23.PlayHttpServerDecorator.REPORT_HTTP_STATUS;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import play.api.mvc.Action;
import play.api.mvc.Headers;
import play.api.mvc.Request;
import play.api.mvc.Result;
import scala.concurrent.Future;

public class PlayAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope onEnter(@Advice.Argument(0) final Request<?> req) {
    final AgentSpan span;
    final ContextScope scope;
    if (activeSpan() == null) {
      Headers headers = req.headers();
      final Context parentContext = DECORATE.extract(headers);
      final Context context = DECORATE.startSpan(headers, parentContext);
      span = spanFromContext(context);
      scope = context.attach();
    } else {
      // An upstream framework (e.g. akka-http, netty) has already started the span.
      // Do not extract the context.
      span = startSpan("play", PLAY_REQUEST);
      span.setMeasured(true);
      scope = span.attachWithCurrent();
    }

    DECORATE.afterStart(span);
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopTraceOnResponse(
      @Advice.Enter final ContextScope playControllerScope,
      @Advice.This final Object thisAction,
      @Advice.Thrown final Throwable throwable,
      @Advice.Argument(0) final Request<?> req,
      @Advice.Return(readOnly = false) final Future<Result> responseFuture) {
    final AgentSpan playControllerSpan = spanFromContext(playControllerScope.context());

    // Call onRequest on return after tags are populated.
    DECORATE.onRequest(playControllerSpan, req, req, getRootContext());

    if (throwable == null) {
      responseFuture.onComplete(
          new RequestCompleteCallback(playControllerScope),
          ((Action<?>) thisAction).executionContext());
    } else {
      DECORATE.onError(playControllerSpan, throwable);
      if (REPORT_HTTP_STATUS) {
        playControllerSpan.setHttpStatusCode(500);
      }
      DECORATE.beforeFinish(playControllerScope.context());
      playControllerSpan.finish();
    }
    playControllerScope.close();
    // span finished in RequestCompleteCallback

    final AgentSpan rootSpan = activeSpan();
    // set the resource name on the upstream akka/netty span if there is one
    if (rootSpan != null) {
      DECORATE.onRequest(rootSpan, req, req, getRootContext());
    }
  }
}
