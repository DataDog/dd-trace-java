package controllers

import actions._
import io.opentracing.{Scope, Span, Tracer}
import io.opentracing.util.GlobalTracer
import javax.inject.Inject
import play.api.libs.ws._
import play.api.mvc._
import play.api.Configuration
import scala.concurrent.{ExecutionContext, Future}

class SController @Inject() (
    cc: ControllerComponents,
    ws: WSClient,
    configuration: Configuration
) extends AbstractController(cc) {

  private implicit val ec: ExecutionContext = cc.executionContext

  private val clientRequestBase =
    configuration
      .getOptional[String]("client.request.base")
      .getOrElse("http://localhost:0/broken/")

  def doGet(id: Option[Int]) =
    SAction1 {
      SAction2 {
        Action.async { implicit request: Request[AnyContent] =>
          val tracer = GlobalTracer.get
          val span   = tracer.buildSpan("do-get").start
          val scope  = tracer.scopeManager.activate(span)

          try {
            val idVal = id.getOrElse(0)
            if (idVal > 0) {
              ws.url(s"$clientRequestBase$idVal").get().map { response =>
                Status(response.status)(s"S Got '${response.body}'")
              }
            } else Future { BadRequest("No ID.") }
          } finally {
            scope.close
            span.finish
          }
        }
      }
    }
}
