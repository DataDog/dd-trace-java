package mule4

import static org.mule.runtime.api.util.MuleTestUtil.muleSpan

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import spock.lang.Shared

class MuleHttpServerForkedTest extends HttpServerTest<MuleTestContainer> {

  // TODO since mule uses reactor core, things sometime propagate to places where they're not closed
  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @Override
  boolean testRedirect() {
    // Dynamic adding of headers to and HttpResponse from inside a mule application seems
    // to be a whole separate can of worms, so let's not try to test it
    return false
  }

  @Override
  boolean testExceptionBody() {
    // The default failure handler response body contains more that the original exception message
    return false
  }

  @Override
  boolean testNotFound() {
    false
  }

  @Override
  void controllerSpan(TraceAssert trace, ServerEndpoint endpoint = null) {
    def expectsError = endpoint == ServerEndpoint.EXCEPTION
    def flowSpan = muleSpan(trace, "mule:flow", "MuleHttpServerTestFlow", null, expectsError)
    muleSpan(trace, "java:new", "Create Handler", flowSpan)
    muleSpan(trace, "java:invoke", "Handle Message", flowSpan, expectsError)
    super.controllerSpan(trace, endpoint)
    if (!expectsError) {
      muleSpan(trace, "mule:set-variable", "Set Response Code", flowSpan)
      muleSpan(trace, "mule:set-payload", "Set Response Body",flowSpan)
    } else {
      muleSpan(trace, "mule:on-error-propagate", "unknown",flowSpan)
    }
  }


  @Override
  int spanCount(ServerEndpoint endpoint) {
    return super.spanCount(endpoint) + (endpoint == ServerEndpoint.EXCEPTION ? 4 : 5)
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integration.mule.enabled", "true")
  }

  @Shared
  Properties buildProperties = {
    Properties props = new Properties()
    props.load(this.class.getResourceAsStream("/test-build.properties"))
    return props
  }.call()

  @Override
  String component() {
    // If we use the GrizzlyDecorator, we need to add Grizzly to the class path, which we don't want
    return "grizzly-filterchain-server"
  }

  @Override
  String expectedOperationName() {
    // If we use the GrizzlyDecorator, we need to add Grizzly to the class path, which we don't want
    return "grizzly.request"
  }

  @Override
  MuleTestContainer startServer(int port) {
    File muleBase = new File(String.valueOf(buildProperties.get("mule.base")))
    MuleTestContainer container = new MuleTestContainer(muleBase)
    container.start()
    def appProperties = new Properties()
    ["test.server.port": "$port", "test.server.host": "localhost"].each {
      // Force cast GStringImpl to String since Mule code does String casts of some properties
      appProperties.put((String) it.key, (String) it.value)
    }
    def app = new URI("file:" + new File(String.valueOf(buildProperties.get(MuleTestApplicationConstants.TEST_APPLICATION_JAR))).canonicalPath)
    container.deploy(app, appProperties)
    return container
  }

  @Override
  void stopServer(MuleTestContainer container) {
    if (container != null) {
      container.undeploy(String.valueOf(buildProperties.get(MuleTestApplicationConstants.TEST_APPLICATION_NAME)))
      container.stop()
    }
  }
}

/**
 * This test wants to check that we built otel tracing (based on the sdk) does not interfere with our
 */
class MuleHttpServerOTELEnabledForkedTest extends MuleHttpServerForkedTest {

  @Override
  MuleTestContainer startServer(int port) {
    System.setProperty("mule.openTelemetry.tracer.exporter.enabled", "true")
    return super.startServer(port)
  }
}
