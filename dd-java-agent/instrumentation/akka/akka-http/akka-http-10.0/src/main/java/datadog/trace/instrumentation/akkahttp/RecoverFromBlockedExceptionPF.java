package datadog.trace.instrumentation.akkahttp;

import akka.http.scaladsl.model.HttpEntity$;
import akka.http.scaladsl.model.HttpProtocols;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.model.StatusCode;
import akka.http.scaladsl.util.FastFuture$;
import akka.japi.JavaPartialFunction;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import scala.PartialFunction;
import scala.collection.immutable.List$;
import scala.compat.java8.JFunction1;
import scala.concurrent.Future;

public class RecoverFromBlockedExceptionPF extends JavaPartialFunction<Throwable, HttpResponse> {
  public static final PartialFunction<Throwable, HttpResponse> INSTANCE =
      new RecoverFromBlockedExceptionPF();
  public static final PartialFunction<Throwable, Future<HttpResponse>> INSTANCE_FUTURE;

  static {
    JFunction1<HttpResponse, Future<HttpResponse>> f = RecoverFromBlockedExceptionPF::valueToFuture;
    INSTANCE_FUTURE = INSTANCE.andThen(f);
  }

  @Override
  public HttpResponse apply(Throwable x, boolean isCheck) throws Exception {
    if (x instanceof BlockingException) {
      if (isCheck) {
        return null;
      }
      AgentSpan agentSpan = AgentTracer.activeSpan();
      if (agentSpan != null) {
        agentSpan.addThrowable(x);
      }

      // will be replaced anyway
      return new HttpResponse(
          StatusCode.int2StatusCode(500),
          List$.MODULE$.empty(),
          HttpEntity$.MODULE$.Empty(),
          HttpProtocols.HTTP$div1$u002E1());
    } else {
      throw noMatch();
    }
  }

  private static <V> Future<V> valueToFuture(V value) {
    return FastFuture$.MODULE$.<V>successful().apply(value);
  }
}
