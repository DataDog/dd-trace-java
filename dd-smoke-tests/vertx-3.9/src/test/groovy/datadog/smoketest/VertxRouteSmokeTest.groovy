package datadog.smoketest

class VertxRouteSmokeTest extends VertxSmokeTest {
  @Override
  protected Set<String> expectedTraces() {
    return ["[netty.request[vertx.route-handler]]"].toSet()
  }

  @Override
  protected String path() {
    return "/routes"
  }
}
