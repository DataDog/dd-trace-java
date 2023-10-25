package datadog.smoketest

class Play28OTelAkkaSmokeTest extends Play28OTelSmokeTest {
  @Override
  String serverProviderName() {
    return "akka-http"
  }

  @Override
  String serverProvider() {
    return "play.core.server.AkkaHttpServerProvider"
  }
}
