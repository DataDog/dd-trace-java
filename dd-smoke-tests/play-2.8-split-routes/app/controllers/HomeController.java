package controllers;

import play.mvc.*;

public class HomeController extends Controller {

  public Result all(Http.Request request) {
    return ok("all");
  }

  public Result post(Http.Request request, String id) {
    return ok("Post #" + id);
  }
}
