package datadog.trace.instrumentation.spray

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE
import spray.http.HttpResponse
import spray.routing.{RequestContext, Route}

import scala.util.control.NonFatal

object SprayHelper {
  def wrapRequestContext(
      ctx: RequestContext,
      span: AgentSpan,
      extracted: AgentSpan.Context.Extracted
  ): RequestContext = {
    ctx.withRouteResponseMapped(message => {
      DECORATE.onRequest(span, ctx, ctx.request, extracted)
      message match {
        case response: HttpResponse => DECORATE.onResponse(span, response)
        case throwable: Throwable   => DECORATE.onError(span, throwable)
        case x                      =>
      }
      DECORATE.beforeFinish(span)
      span.finish()
      message
    })
  }

  def wrapRoute(route: Route): Route = { ctx =>
    {
      DECORATE.onRequest(activeSpan(), ctx, ctx.request, null)
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
