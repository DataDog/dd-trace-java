import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.servlet3.TestServlet3
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.server.session.SessionHandler

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN
import static org.junit.jupiter.api.Assumptions.assumeTrue

abstract class Jetty70Test extends HttpServerTest<Server> {

  class JettyServer implements HttpServer {
    def port = 0
    final server = new Server(0) // select random open port

    JettyServer() {
      final sessionHandler = new SessionHandler()
      sessionHandler.handler = handler()
      server.setHandler(sessionHandler)
      server.addBean(errorHandler)
    }

    @Override
    void start() {
      server.start()
      port = server.connectors[0].localPort
      assert port > 0
    }

    @Override
    void stop() {
      server.stop()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }

  static errorHandler = new ErrorHandler() {
    @Override
    protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
      Throwable th = (Throwable) request.getAttribute("javax.servlet.error.exception")
      message = th ? th.message : message
      if (message) {
        writer.write(message)
      }
    }
  }

  @Override
  HttpServer server() {
    return new JettyServer()
  }

  AbstractHandler handler() {
    TestHandler.INSTANCE
  }

  @Override
  String component() {
    return "jetty-server"
  }

  @Override
  String expectedOperationName() {
    return component()
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
  boolean testBlocking() {
    true
  }

  @Override
  boolean hasExtraErrorInformation() {
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
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean testSessionId() {
    true
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
    // This pins the pre-existing blocking behavior for jetty-appsec-7.0's UrlEncodedInstrumentation
    // (and, where enabled, MultipartHelper/RequestExtractContentParametersInstrumentation) after the
    // block-telemetry-3 changes wired the reportBlockFailure() call into these advice classes.
    // reportBlockFailure() itself remains unreachable through this end-to-end test: the real
    // JettyBlockingHelper.tryCommitBlockingResponse always returns true for a genuine attempt
    // (see .claude-invariants.md), so no test here can force that branch.
    response.code() == 413
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    !handlerRan

    where:
    variant      | executeTest          | endpoint        | body
    'urlencoded' | testBodyUrlencoded() | BODY_URLENCODED | RequestBody.create(MediaType.get('application/x-www-form-urlencoded'), 'a=x')
    'multipart'  | testBodyMultipart()  | BODY_MULTIPART  | new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart('a', 'x').build()
  }

  static class TestHandler extends AbstractHandler {
    private static final TestHandler INSTANCE = new TestHandler()

    final TestServlet3.Sync testServlet3 = new TestServlet3.Sync() {
      @Override
      HttpServerTest.ServerEndpoint determineEndpoint(HttpServletRequest req) {
        HttpServerTest.ServerEndpoint.forPath(req.requestURI)
      }

      @Override
      void service(HttpServletRequest req, HttpServletResponse resp) {
        HttpServerTest.ServerEndpoint endpoint = determineEndpoint(req)
        if (endpoint == UNKNOWN || endpoint == NOT_FOUND) {
          resp.status = NOT_FOUND.status
          resp.writer.print(NOT_FOUND.body)
        } else {
          super.service(req, resp)
        }
      }
    }

    static void handleRequest(Request request, HttpServletResponse response) {
      INSTANCE.testServlet3.service(request, response)
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if (baseRequest.dispatcherType.name() != "ERROR") {
        handleRequest(baseRequest, response)
        baseRequest.handled = true
      } else {
        errorHandler.handle(target, baseRequest, response, response)
      }
    }
  }
}

class Jetty70V0ForkedTest extends Jetty70Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

class Jetty70V1ForkedTest extends Jetty70Test implements TestingGenericHttpNamingConventions.ServerV1 {
}
