package datadog.trace.instrumentation.play24;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.play24.PlayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.play24.PlayHttpServerDecorator.PLAY_ACTION;
import static datadog.trace.instrumentation.play24.PlayHttpServerDecorator.PLAY_REQUEST;
import static datadog.trace.instrumentation.play24.PlayHttpServerDecorator.REPORT_HTTP_STATUS;

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
  public static ContextScope onEnter(@Advice.Argument(value = 0, readOnly = false) Request<?> req) {
    final AgentSpan span;
    final ContextScope scope;

    // If we have already added a `play.request` span, then don't do it again
    if (req.tags().contains("_dd_HasPlayRequestSpan")) {
      return null;
    }

    if (activeSpan() == null) {
      final Headers headers = req.headers();
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

    req = RequestHelper.withTag(req, "_dd_HasPlayRequestSpan", "true");

    DECORATE.onRequest(span, req, req, rootContext());

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopTraceOnResponse(
      @Advice.Enter final ContextScope playControllerScope,
      @Advice.This final Object thisAction,
      @Advice.Thrown final Throwable throwable,
      @Advice.Argument(0) final Request<?> req,
      @Advice.Return(readOnly = false) Future<Result> responseFuture) {

    if (playControllerScope == null) {
      return;
    }

    final AgentSpan playControllerSpan = spanFromContext(playControllerScope.context());

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

  // Unused method for muzzle to allow only 2.4-2.5
  public static void muzzleCheck() {
    play.libs.Akka.system();
  }
}
