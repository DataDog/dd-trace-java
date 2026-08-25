package controllers;

import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class HomeController extends Controller {

  public Result all(Http.Request request) {
    return ok("all");
  }

  public Result post(Http.Request request, String id) {
    return ok("Post #" + id);
  }
}
