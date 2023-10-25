package datadog.smoketest

class Play28OTelNettySmokeTest extends Play28OTelSmokeTest {
  @Override
  String serverProviderName() {
    return "netty"
  }

  @Override
  String serverProvider() {
    return "play.core.server.NettyServerProvider"
  }
}
