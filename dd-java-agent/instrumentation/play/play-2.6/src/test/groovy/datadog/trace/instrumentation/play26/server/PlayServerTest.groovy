package datadog.trace.instrumentation.play26.server

class PlayServerTest extends AbstractPlayServerTest {
  // Disabling involving Appsec / Instrumentation Gateway, they are tested in play-appsec-2.x modules

  @Override
  boolean testBlocking() {
    false
  }

  @Override
  boolean testBlockingOnResponse() {
    false
  }

  @Override
  boolean testRequestBody() {
    false
  }

  @Override
  boolean testBodyJson() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    false
  }

  @Override
  boolean testBodyMultipart() {
    false
  }

  @Override
  boolean testResponseBodyJson() {
    false
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    null
  }

  @Override
  boolean testBodyXml() {
    false
  }
}
