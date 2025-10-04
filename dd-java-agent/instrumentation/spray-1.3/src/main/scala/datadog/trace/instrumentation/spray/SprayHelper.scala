package datadog.trace.instrumentation.spray

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getRootContext;
import datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE
import spray.http.HttpResponse
import spray.routing.{RequestContext, Route}

import scala.util.control.NonFatal

object SprayHelper {
  def wrapRequestContext(
      ctx: RequestContext,
      span: AgentSpan,
      parentContext: Context,
      scope: ContextScope
  ): RequestContext = {
    ctx.withRouteResponseMapped(message => {
      DECORATE.onRequest(span, ctx, ctx.request, parentContext)
      message match {
        case response: HttpResponse => DECORATE.onResponse(span, response)
        case throwable: Throwable   => DECORATE.onError(span, throwable)
        case x                      =>
      }
      DECORATE.beforeFinish(scope.context())
      scope.close()
      span.finish()
      message
    })
  }

  def wrapRoute(route: Route): Route = { ctx =>
    {
      DECORATE.onRequest(activeSpan(), ctx, ctx.request, getRootContext())
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
