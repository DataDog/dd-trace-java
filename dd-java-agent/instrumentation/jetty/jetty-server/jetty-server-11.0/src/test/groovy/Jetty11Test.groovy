import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.servlet5.HtmlRumServlet
import datadog.trace.instrumentation.servlet5.TestServlet5
import datadog.trace.instrumentation.servlet5.XmlRumServlet
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static org.junit.jupiter.api.Assumptions.assumeTrue

abstract class Jetty11Test extends HttpServerTest<Server> {
  @Override
  HttpServer server() {
    new JettyServer(handler(), useWebsocketPojoEndpoint())
  }

  protected Handler handler() {
    JettyServer.servletHandler(TestServlet5)
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ['servlet.context': '/context-path', 'servlet.path': endpoint.path]
  }

  @Override
  String component() {
    'jetty-server'
  }

  @Override
  String expectedOperationName() {
    operation()
  }

  @Override
  String expectedServiceName() {
    'context-path'
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
  boolean testBlocking() {
    true
  }

  @Override
  boolean testUserBlocking() {
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
  boolean testSessionId() {
    true
  }

  protected boolean useWebsocketPojoEndpoint() {
    false
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
    // This pins the pre-existing blocking behavior for jetty-appsec-11.0's MultipartHelper and
    // RequestExtractContentParametersInstrumentation after the block-telemetry-3 changes wired
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

class Jetty11V0ForkedTest extends Jetty11Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

class Jetty11V1ForkedTest extends Jetty11Test implements TestingGenericHttpNamingConventions.ServerV1 {
  protected boolean useWebsocketPojoEndpoint() {
    true
  }
}

class JettyRumInjectionForkedTest extends Jetty11V0ForkedTest {
  @Override
  boolean testRumInjection() {
    true
  }

  @Override
  protected Handler handler() {
    def handler = JettyServer.servletHandler(TestServlet5)
    handler.addServlet(HtmlRumServlet, "/gimme-html")
    handler.addServlet(XmlRumServlet, "/gimme-xml")
    handler
  }
}
