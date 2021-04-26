package datadog.trace.instrumentation.spray

import datadog.trace.bootstrap.instrumentation.api.AgentTracer.{activeScope, activeSpan}
import datadog.trace.bootstrap.instrumentation.api.{AgentSpan, Tags}
import datadog.trace.context.TraceScope
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
      log.debug(s"wrap context message $ctx $message")
      message match {
        case response: HttpResponse =>
          span.setTag(Tags.HTTP_STATUS, response.status.intValue)
        case x =>
          log.debug("Unexpected message to Spray response processing func: $x")
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
      log.debug(s"before route $ctx")
      try route(ctx)
      catch {
        case NonFatal(e) =>
          val span = activeSpan()
          if (span != null) {
            span.setTag(Tags.ERROR, true)
            span.setError(true)
            span.addThrowable(e)
          }
          throw e
      } finally log.debug(s"after route $ctx")
    }
  }
}
