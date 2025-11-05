package server;

import io.vertx.ext.web.Router;

public class VertxSubrouterTestServer extends VertxTestServer {
  @Override
  protected Router customizeAfterRoutes(Router configured) {
    Router router = Router.router(vertx);
    router.mountSubRouter("/sub/", configured);
    return router;
  }
}
