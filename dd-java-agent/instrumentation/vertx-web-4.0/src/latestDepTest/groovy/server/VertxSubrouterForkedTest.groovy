package server


import io.vertx.core.AbstractVerticle

class VertxSubrouterForkedTest extends VertxHttpServerForkedTest {
  @Override
  protected Class<AbstractVerticle> verticle() {
    VertxSubrouterTestServer
  }

  @Override
  String routerBasePath() {
    return "/sub/"
  }

  @Override
  boolean testEncodedQuery() {
    // FIXME: test instrumentation gateway callback ... is failing for latest
    false
  }
}
