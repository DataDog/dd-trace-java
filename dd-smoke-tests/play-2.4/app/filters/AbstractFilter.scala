package filters

import io.opentracing.Scope
import io.opentracing.Span
import io.opentracing.Tracer
import io.opentracing.util.GlobalTracer
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._
import scala.concurrent.Future

abstract class AbstractFilter(val operationName: String, val wrap: Boolean) extends Filter {
  def this(operationName: String) {
    this(operationName, false)
  }

  override def apply(
      nextFilter: RequestHeader => Future[Result]
  )(requestHeader: RequestHeader): Future[Result] = {
    val tracer      = GlobalTracer.get
    val startedSpan = if (wrap) tracer.buildSpan(operationName).start else null
    val outerScope  =
      if (wrap) tracer.scopeManager.activate(startedSpan) else null
    try {
      nextFilter(requestHeader).map { result =>
        val span =
          if (wrap) startedSpan else tracer.buildSpan(operationName).start
        var innerScope: Scope = null;
        try {
          innerScope = tracer.scopeManager.activate(span)
          // Yes this does no real work
          result
        } finally {
          if (innerScope != null) innerScope.close()
          span.finish
        }
      }
    } finally {
      if (wrap) outerScope.close
    }
  }
}
