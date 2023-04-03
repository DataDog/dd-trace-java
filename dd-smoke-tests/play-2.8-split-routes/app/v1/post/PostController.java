package v1.post;

import play.mvc.*;

public class PostController extends Controller {

  public Result all(Http.Request request) {
    return ok("all");
  }

  public Result post(Http.Request request, String id) {
    return ok("Post #" + id);
  }
}
