package actions

import io.opentelemetry.api.GlobalOpenTelemetry
import play.api.mvc._

import scala.concurrent.Future

abstract class AbstractSAction[A](
    private val action: Action[A],
    private val operationName: String
) extends Action[A] {
  def apply(request: Request[A]): Future[Result] = {
    val tracer = GlobalOpenTelemetry.getTracer("play-test")
    val span   = tracer.spanBuilder(operationName).startSpan()
    val scope  = span.makeCurrent()
    try return action(request)
    finally {
      scope.close()
      span.end()
    }
  }

  override def parser           = action.parser
  override def executionContext = action.executionContext
}

case class SAction1[A](action: Action[A]) extends AbstractSAction[A](action, "action1") {}

case class SAction2[A](action: Action[A]) extends AbstractSAction[A](action, "action2") {}
