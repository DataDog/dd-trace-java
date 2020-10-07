package controllers;

import actions.*;
import play.mvc.*;
import play.mvc.With;

public class HomeController extends Controller {

  @With({Action1.class, Action2.class})
  public Result doGet(Integer id) {
    if (id > 0) {
      return ok("Welcome " + id + ".");
    } else {
      return badRequest("No ID.");
    }
  }
}
