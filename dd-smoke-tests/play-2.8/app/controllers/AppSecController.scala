package controllers

import javax.inject._
import play.api.mvc._

@Singleton
class AppSecController @Inject() (cc: ControllerComponents) extends AbstractController(cc) {

  def apiSecuritySampling(statusCode: Int, test: String): Action[AnyContent] = Action {
    Status(statusCode)("EXECUTED")
  }

  def apiResponse(): Action[AnyContent] = Action { request =>
    request.body match {
      case AnyContentAsJson(data) => Ok(data).as("application/json")
      case _                      => BadRequest("No JSON")
    }
  }

}
