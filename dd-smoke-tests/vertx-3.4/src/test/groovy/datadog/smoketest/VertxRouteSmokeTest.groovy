package datadog.smoketest

import spock.lang.IgnoreIf

@IgnoreIf({
  // TODO https://github.com/eclipse-vertx/vert.x/issues/2172
  new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0)
})
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
