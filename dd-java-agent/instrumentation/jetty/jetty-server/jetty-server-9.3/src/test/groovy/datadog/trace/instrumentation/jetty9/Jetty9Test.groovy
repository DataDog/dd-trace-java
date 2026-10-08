package datadog.trace.instrumentation.jetty9

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import test.JettyServer
import test.TestHandler

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static org.junit.jupiter.api.Assumptions.assumeTrue

abstract class Jetty9Test extends HttpServerTest<Server> {

  @Override
  HttpServer server() {
    new JettyServer(handler(), useWebsocketPojoEndpoint())
  }

  AbstractHandler handler() {
    TestHandler.INSTANCE
  }

  @Override
  String component() {
    "jetty-server"
  }

  @Override
  String expectedOperationName() {
    operation()
  }

  protected boolean useWebsocketPojoEndpoint() {
    false
  }


  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testRequestBodyISVariant() {
    true
  }

  @Override
  boolean testUserBlocking() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyFilenames() {
    true
  }

  @Override
  boolean testBodyFilenamesCalledOnce() {
    true
  }

  @Override
  boolean testBodyFilenamesCalledOnceCombined() {
    true
  }

  @Override
  boolean testBodyFilesContent() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }

  @Override
  boolean testWebsockets() {
    return super.testWebsockets() && (getServer() as JettyServer).websocketAvailable
  }

  def 'test blocking of multipart and urlencoded request body is pinned after block-telemetry-3 wiring #variant'() {
    setup:
    assumeTrue(testBlocking())
    assumeTrue(executeTest)

    def request = request(endpoint, 'POST', body)
      .header('x-block-body-converted', 'true')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    // This pins the pre-existing blocking behavior for jetty-appsec-9.2/9.3/9.4's MultipartHelper
    // and RequestExtractContentParametersInstrumentation after the block-telemetry-3 changes wired
    // the reportBlockFailure() call into these advice classes. reportBlockFailure() itself remains
    // unreachable through this end-to-end test: the real JettyBlockingHelper.tryCommitBlockingResponse
    // always returns true for a genuine attempt (see .claude-invariants.md), so no test here can
    // force that branch.
    response.code() == 413
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    !handlerRan

    where:
    variant      | executeTest          | endpoint        | body
    'urlencoded' | testBodyUrlencoded() | BODY_URLENCODED | RequestBody.create(MediaType.get('application/x-www-form-urlencoded'), 'a=x')
    'multipart'  | testBodyMultipart()  | BODY_MULTIPART  | new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart('a', 'x').build()
  }
}

class Jetty9V0ForkedTest extends Jetty9Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

class Jetty9V1ForkedTest extends Jetty9Test implements TestingGenericHttpNamingConventions.ServerV1 {
}
