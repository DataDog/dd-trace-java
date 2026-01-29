package datadog.trace.instrumentation.akkahttp;

import akka.http.scaladsl.server.RequestContext;
import akka.http.scaladsl.server.RouteResult;
import akka.japi.JavaPartialFunction;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import scala.Function1;
import scala.concurrent.Future;

/**
 * Runs before the default exception handler in {@link
 * akka.http.scaladsl.server.ExceptionHandler$#default}, which usually completes with a 500, that
 * the exception may be recorded.
 */
public class MarkSpanAsErroredPF
    extends JavaPartialFunction<Throwable, scala.Function1<RequestContext, Future<RouteResult>>> {
  public static final JavaPartialFunction INSTANCE = new MarkSpanAsErroredPF();

  private MarkSpanAsErroredPF() {}

  @Override
  public Function1<RequestContext, Future<RouteResult>> apply(Throwable x, boolean isCheck)
      throws Exception {
    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan != null) {
      agentSpan.addThrowable(x);
    }
    throw noMatch();
  }
}
