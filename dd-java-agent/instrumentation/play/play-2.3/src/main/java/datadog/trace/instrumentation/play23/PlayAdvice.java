package datadog.trace.instrumentation.play23;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.play23.PlayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.play23.PlayHttpServerDecorator.PLAY_ACTION;
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
      // Keep the existing trace context instead of extracting the request headers again.
      span = startSpan(PLAY_ACTION.toString(), PLAY_REQUEST);
      span.setMeasured(true);
      scope = span.attachWithContext();
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
      @Advice.Return(readOnly = false) Future<Result> responseFuture) {
    final AgentSpan playControllerSpan = spanFromContext(playControllerScope.context());

    DECORATE.onRequest(playControllerSpan, req, req, rootContext());

    if (throwable == null) {
      // Finish the Play span before the server can complete the response and finish its root span.
      responseFuture =
          responseFuture.andThen(
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

    final AgentSpan parentSpan = activeSpan();
    if (parentSpan != null) {
      DECORATE.onRequest(parentSpan, req, req, rootContext());
    }
  }
}
