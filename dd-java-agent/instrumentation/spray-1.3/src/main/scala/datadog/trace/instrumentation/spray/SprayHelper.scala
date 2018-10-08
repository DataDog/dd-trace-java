package datadog.trace.instrumentation.spray

import datadog.trace.api.cache.RadixTreeCache
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.{
  activeScope,
  activeSpan
}
import datadog.trace.bootstrap.instrumentation.api.{AgentSpan, Tags}
import datadog.trace.context.TraceScope
import datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE
import spray.http.HttpResponse
import spray.routing.{RequestContext, Route}

import scala.util.control.NonFatal

object SprayHelper {
  private val log = org.slf4j.LoggerFactory.getLogger(SprayHelper.getClass);

  def wrapRequestContext(
      ctx: RequestContext,
      span: AgentSpan
  ): RequestContext = {
    log.debug(s"Wrapping context $ctx")
    ctx.withRouteResponseMapped(message => {
      DECORATE.onRequest(span, ctx, ctx.request, null)
      log.debug(s"wrap context message $ctx $message")
      message match {
        case response: HttpResponse =>
          span.setTag(
            Tags.HTTP_STATUS,
            RadixTreeCache.HTTP_STATUSES.get(response.status.intValue)
          )
          if (404 == response.status.intValue) {
            span.setResourceName("404")
          } else if (response.status.intValue >= 500) {
            span.setError(true)
          }
        case x =>
          log.debug(s"Unexpected message to Spray response processing func: $x")
      }
      val scope = activeScope()
      if (scope.isInstanceOf[TraceScope])
        scope.setAsyncPropagation(false)
      span.finish()
      message
    })
  }

  def wrapRoute(route: Route): Route = {
    log.debug("Wrapping route")
    ctx => {
      log.debug(s"decorate ctx: $ctx")
      DECORATE.onRequest(activeSpan(), ctx, ctx.request, null)
      try route(ctx)
      catch {
        case NonFatal(e) =>
          val span = activeSpan()
          if (span != null) {
            DECORATE.onError(span, e)
          }
          throw e
      } finally log.debug(s"after route $ctx")
    }
  }
}
