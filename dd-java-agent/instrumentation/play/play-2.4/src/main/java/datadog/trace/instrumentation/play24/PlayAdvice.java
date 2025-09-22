package datadog.trace.instrumentation.play24;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getRootContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.play24.PlayHttpServerDecorator.DECORATE;
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
      // An upstream framework (e.g. akka-http, netty) has already started the span.
      // Do not extract the context.
      span = startSpan("play", PLAY_REQUEST);
      span.setMeasured(true);
      scope = span.attach();
    }

    DECORATE.afterStart(span);

    req = RequestHelper.withTag(req, "_dd_HasPlayRequestSpan", "true");

    // Moved from OnMethodExit
    // Call onRequest on return after tags are populated.
    DECORATE.onRequest(span, req, req, getRootContext());

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopTraceOnResponse(
      @Advice.Enter final ContextScope playControllerScope,
      @Advice.This final Object thisAction,
      @Advice.Thrown final Throwable throwable,
      @Advice.Argument(0) final Request<?> req,
      @Advice.Return(readOnly = false) final Future<Result> responseFuture) {

    if (playControllerScope == null) {
      return;
    }

    final AgentSpan playControllerSpan = spanFromContext(playControllerScope.context());

    if (throwable == null) {
      responseFuture.onComplete(
          new RequestCompleteCallback(playControllerSpan),
          ((Action<?>) thisAction).executionContext());
    } else {
      DECORATE.onError(playControllerSpan, throwable);
      if (REPORT_HTTP_STATUS) {
        playControllerSpan.setHttpStatusCode(500);
      }
      DECORATE.beforeFinish(playControllerSpan);
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

  // Unused method for muzzle to allow only 2.4-2.5
  public static void muzzleCheck() {
    play.libs.Akka.system();
  }
}
