package controllers

import actions._
import io.opentelemetry.api.GlobalOpenTelemetry

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
          val tracer = GlobalOpenTelemetry.getTracer("play-test")
          val span   = tracer.spanBuilder("do-get").startSpan()
          val scope  = span.makeCurrent()

          try {
            val idVal = id.getOrElse(0)
            if (idVal > 0) {
              ws.url(s"$clientRequestBase$idVal").get().map { response =>
                Status(response.status)(s"S Got '${response.body}'")
              }
            } else Future { BadRequest("No ID.") }
          } finally {
            scope.close()
            span.end()
          }
        }
      }
    }
}
