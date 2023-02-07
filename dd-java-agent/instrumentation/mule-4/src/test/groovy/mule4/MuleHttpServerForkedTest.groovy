package mule4

import datadog.trace.agent.test.base.HttpServerTest
import spock.lang.Shared

import static mule4.MuleTestApplicationConstants.*

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
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("integration.grizzly-filterchain.enabled", "true")
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
    def app = new URI("file:" + new File(String.valueOf(buildProperties.get(TEST_APPLICATION_JAR))).canonicalPath)
    container.deploy(app, appProperties)
    return container
  }

  @Override
  void stopServer(MuleTestContainer container) {
    container.undeploy(String.valueOf(buildProperties.get(TEST_APPLICATION_NAME)))
    container.stop()
  }
}
