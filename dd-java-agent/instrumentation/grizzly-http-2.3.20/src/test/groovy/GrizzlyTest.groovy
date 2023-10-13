import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator
import org.glassfish.grizzly.http.server.HttpServer

class GrizzlyTest extends HttpServerTest<HttpServer> {
  @Override
  datadog.trace.agent.test.base.HttpServer server() {
    new GrizzlyServer(resource())
  }

  Class<ServiceResource> resource() {
    ServiceResource
  }

  @Override
  String component() {
    return GrizzlyDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return GrizzlyDecorator.GRIZZLY_REQUEST.toString()
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  //@Ignore("https://github.com/DataDog/dd-trace-java/pull/5213")
  @Override
  boolean testBadUrl() {
    false
  }

  @Override
  String testPathParam() {
    "/path/?/param"
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    [id: ['123']]
  }

}
