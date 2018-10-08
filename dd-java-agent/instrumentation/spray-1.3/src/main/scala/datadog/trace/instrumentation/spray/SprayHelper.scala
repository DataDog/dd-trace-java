package datadog.trace.instrumentation.spray

import java.util.Collections

import datadog.trace.context.TraceScope
import io.opentracing.Span
import io.opentracing.log.Fields.ERROR_OBJECT
import io.opentracing.tag.Tags
import io.opentracing.util.GlobalTracer
import spray.http.HttpResponse
import spray.routing.{RequestContext, Route}

import scala.util.control.NonFatal

object SprayHelper {
  private val log = org.slf4j.LoggerFactory.getLogger(SprayHelper.getClass);

  def wrapRequestContext(ctx: RequestContext, span: Span): RequestContext = {
    log.debug(s"!!!! wrap context $ctx")
    ctx.withRouteResponseMapped(message => {
      log.debug(s"!!!! wrap context message $ctx $message")
      message match {
        case response: HttpResponse => Tags.HTTP_STATUS.set(span, response.status.intValue)
        case x => log.debug("Unexpected message to Spray response processing func: $x")
      }
      if (GlobalTracer.get.scopeManager.active.isInstanceOf[TraceScope])
        GlobalTracer.get.scopeManager.active.asInstanceOf[TraceScope].setAsyncPropagation(false)
      span.finish()
      message
    })
  }

  def wrapRoute(route: Route): Route = {
    log.debug("!!!! Wrapping route")
    ctx => {
      log.debug(s"!!!! before route $ctx")
      try route(ctx)
      catch {
        case NonFatal(e) =>
          val scope = GlobalTracer.get.scopeManager.active
          if (scope != null) {
            val span = scope.span
            Tags.ERROR.set(span, true)
            span.log(Collections.singletonMap(ERROR_OBJECT, e))
          }
          throw e
      }
      finally log.debug(s"!!!! after route $ctx")
    }
  }
}
