package controllers

import play.api.mvc.{Action, AnyContent, AnyContentAsJson, Controller}

class AppSecController extends Controller {

  def apiSecuritySampling(statusCode: Int, test: String) = Action {
    Status(statusCode)("EXECUTED")
  }

  def apiResponse(): Action[AnyContent] = Action { request =>
    request.body match {
      case AnyContentAsJson(data) => Ok(data).as("application/json")
      case _                      => BadRequest("No JSON")
    }
  }

}
