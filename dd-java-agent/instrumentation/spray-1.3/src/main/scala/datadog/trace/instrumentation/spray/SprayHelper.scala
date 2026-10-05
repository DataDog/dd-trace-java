package datadog.trace.instrumentation.spray

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;
import datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE
import spray.http.HttpResponse
import spray.routing.{RequestContext, Route}

import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal

object SprayHelper {
  private val ResponseComplete = 1
  private val ScopeClosed      = 2
  private val Complete         = ResponseComplete | ScopeClosed

  def responseComplete(span: AgentSpan, completion: AtomicInteger): Unit =
    complete(span, completion, ResponseComplete)

  def scopeClosed(span: AgentSpan, completion: AtomicInteger): Unit =
    complete(span, completion, ScopeClosed)

  private def complete(span: AgentSpan, completion: AtomicInteger, event: Int): Unit = {
    var current = completion.get()
    while ((current & event) == 0) {
      if (completion.compareAndSet(current, current | event)) {
        // The second event observes the first event's work and owns finishing the span.
        if ((current | event) == Complete) {
          span.finish()
        }
        return
      }
      current = completion.get()
    }
  }

  def wrapRequestContext(
      ctx: RequestContext,
      span: AgentSpan,
      parentContext: Context,
      context: Context,
      completion: AtomicInteger
  ): RequestContext = {
    ctx.withRouteResponseMapped(message => {
      DECORATE.onRequest(span, ctx, ctx.request, parentContext)
      message match {
        case response: HttpResponse => DECORATE.onResponse(span, response)
        case throwable: Throwable   => DECORATE.onError(span, throwable)
        case x                      =>
      }
      DECORATE.beforeFinish(context)
      responseComplete(span, completion)
      message
    })
  }

  def wrapRoute(route: Route): Route = { ctx =>
    {
      DECORATE.onRequest(activeSpan(), ctx, ctx.request, rootContext())
      try route(ctx)
      catch {
        case NonFatal(e) =>
          val span = activeSpan()
          if (span != null) {
            DECORATE.onError(span, e)
          }
          throw e
      }
    }
  }
}
