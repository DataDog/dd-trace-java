import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator
import datadog.trace.test.util.Flaky
import org.glassfish.grizzly.http.server.HttpServer

class GrizzlyTest extends HttpServerTest<HttpServer> {
  @Override
  boolean useStrictTraceWrites() {
    // FIXME: ASM blocking test fails with "Interaction with TraceSegment after root span has already finished"
    false
  }

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

  @Flaky("https://github.com/DataDog/dd-trace-java/issues/6933")
  @Override
  boolean testBlocking() {
    "false" != System.getProperty("run.flaky.tests") // Set when using -PskipFlakyTests gradle parameter
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
