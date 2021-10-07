package actions

import io.opentracing.{Scope, Span, Tracer}
import io.opentracing.util.GlobalTracer
import play.api.mvc._
import scala.concurrent.Future

abstract class AbstractSAction[A](
    private val action: Action[A],
    private val operationName: String
) extends Action[A] {
  def apply(request: Request[A]): Future[Result] = {
    val tracer = GlobalTracer.get
    val span   = tracer.buildSpan(operationName).start
    val scope  = tracer.scopeManager.activate(span)
    try return action(request)
    finally {
      scope.close
      span.finish
    }
  }

  override def parser           = action.parser
  override def executionContext = action.executionContext
}

case class SAction1[A](action: Action[A]) extends AbstractSAction[A](action, "action1") {}

case class SAction2[A](action: Action[A]) extends AbstractSAction[A](action, "action2") {}
