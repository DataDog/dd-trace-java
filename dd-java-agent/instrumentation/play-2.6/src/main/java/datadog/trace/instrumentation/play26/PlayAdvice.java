package datadog.trace.instrumentation.play26;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getRootContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.play26.PlayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.play26.PlayHttpServerDecorator.PLAY_REQUEST;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import net.bytebuddy.asm.Advice;
import play.api.mvc.Action;
import play.api.mvc.Headers;
import play.api.mvc.Request;
import play.api.mvc.Result;
import scala.concurrent.Future;

public class PlayAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope onEnter(@Advice.Argument(value = 0, readOnly = false) Request<?> req) {
    final Context parentContext;
    final AgentSpan span;
    final ContextScope scope;

    // If we have already added a `play.request` span, then don't do it again
    if (req.attrs().contains(HasPlayRequestSpan.KEY)) {
      return null;
    }

    if (activeSpan() == null) {
      final Headers headers = req.headers();
      parentContext = DECORATE.extract(headers);
      final Context context = DECORATE.startSpan(headers, parentContext);
      span = spanFromContext(context);
      scope = context.attach();
    } else {
      // An upstream framework (e.g. akka-http, netty) has already started the span.
      // Do not extract the context.
      parentContext = getRootContext();
      span = startSpan("play", PLAY_REQUEST);
      scope = span.attach();
    }

    span.setMeasured(true);
    DECORATE.afterStart(span);

    req = req.addAttr(HasPlayRequestSpan.KEY, HasPlayRequestSpan.INSTANCE);

    // Moved from OnMethodExit
    // Call onRequest on return after tags are populated.
    DECORATE.onRequest(span, req, req, parentContext);

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
          new RequestCompleteCallback(playControllerScope),
          ((Action<?>) thisAction).executionContext());
    } else {
      DECORATE.onError(playControllerSpan, throwable);
      DECORATE.beforeFinish(playControllerScope.context());
      playControllerSpan.finish();
    }
    playControllerScope.close();
    // span finished in RequestCompleteCallback

    final AgentSpan rootSpan = activeSpan();
    // set the resource name on the upstream akka/netty span if there is one
    if (rootSpan != null && playControllerSpan.getResourceName() != null) {
      rootSpan.setResourceName(
          playControllerSpan.getResourceName(), ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    }
  }
}
