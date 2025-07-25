package datadog.trace.instrumentation.akkahttp106;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.akkahttp106.AkkaHttpClientDecorator.AKKA_CLIENT_REQUEST;
import static datadog.trace.instrumentation.akkahttp106.AkkaHttpClientDecorator.DECORATE;

import akka.http.scaladsl.HttpExt;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import scala.concurrent.Future;

public class SingleRequestAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope methodEnter(
      @Advice.Argument(value = 0, readOnly = false) HttpRequest request) {
    final AkkaHttpClientHelpers.AkkaHttpHeaders headers =
        new AkkaHttpClientHelpers.AkkaHttpHeaders(request);
    if (headers.hadSpan()) {
      return null;
    }

    final AgentSpan span = startSpan("akka-http", AKKA_CLIENT_REQUEST);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    if (request != null) {
      DECORATE.injectContext(getCurrentContext().with(span), request, headers);
      // Request is immutable, so we have to assign new value once we update headers
      request = headers.getRequest();
    }
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.This final HttpExt thiz,
      @Advice.Return final Future<HttpResponse> responseFuture,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }

    final AgentSpan span = scope.span();

    if (throwable == null) {
      responseFuture.onComplete(
          new AkkaHttpClientHelpers.OnCompleteHandler(span), thiz.system().dispatcher());
    } else {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.finish();
    }
    scope.close();
  }
}
