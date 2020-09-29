package datadog.smoketest

class PlayAkkaSmokeTest extends PlaySmokeTest {
  @Override
  String serverProviderName() {
    return "akka-http"
  }

  @Override
  String serverProvider() {
    return "play.core.server.AkkaHttpServerProvider"
  }

  @Override
  Set<String> expectedTraces() {
    return ["[akka-http.request[play.request]]"].toSet()
  }
}
