package controllers

import javax.inject._
import play.api.mvc._

@Singleton
class AppSecController @Inject() (cc: ControllerComponents) extends AbstractController(cc) {

  def apiSecuritySampling(statusCode: Int, test: String): Action[AnyContent] = Action {
    Status(statusCode)("EXECUTED")
  }

}
