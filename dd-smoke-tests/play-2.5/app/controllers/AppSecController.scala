package controllers

import play.api.mvc.{Action, AnyContent, Controller}

class AppSecController extends Controller {

  def apiSecuritySampling(statusCode: Int, test: String): Action[AnyContent] = Action {
    Status(statusCode)("EXECUTED")
  }

}
