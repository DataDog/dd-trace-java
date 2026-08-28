import datadog.trace.agent.test.base.HttpServerTest;
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions;
import java.io.IOException;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for multipart filename extraction on Jetty 8.x.
 *
 * <p>Jetty 8.x introduced Servlet 3.0 and {@code getParts()}, which is the only entry point for
 * multipart processing in this version range (there is no {@code extractContentParameters()}
 * instrumentation like in 9.3+). The handler must therefore call {@code getParts()} explicitly
 * before {@code getParameterMap()} so that multipart form fields are visible to the servlet.
 *
 * <p>Only activated for the {@code latestDepForkedTest} Gradle task (Jetty 8.x). The {@code
 * test.dd.filenames} system property gates execution, preventing these tests from running against
 * Jetty 7.6 where {@code getParts()} does not exist.
 */
abstract class Jetty8LatestDepForkedTest extends Jetty76Test {

  @Override
  public AbstractHandler handler() {
    return new Jetty8TestHandler();
  }

  @Override
  public boolean testBodyMultipart() {
    return true;
  }

  @Override
  public boolean testBodyFilenames() {
    return true;
  }

  @Override
  public boolean testBodyFilenamesCalledOnce() {
    // Jetty 8.x has no _multiParts field guard; getParts() called multiple times
    // (BODY_MULTIPART_REPEATED) fires the event more than once.
    return false;
  }

  @Override
  public boolean testBodyFilenamesCalledOnceCombined() {
    // Jetty 8.x has no _contentParameters field guard; BODY_MULTIPART_COMBINED
    // fires the event on the getParts() call regardless of prior parameterMap access.
    return false;
  }

  @Override
  public boolean testBodyFilesContent() {
    return true;
  }

  static class Jetty8TestHandler extends AbstractHandler {
    private static final MultipartConfigElement MULTIPART_CONFIG =
        new MultipartConfigElement(System.getProperty("java.io.tmpdir"));

    @Override
    public void handle(
        String target,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException, ServletException {
      if (!baseRequest.getDispatcherType().name().equals("ERROR")) {
        // Enable Servlet 3.0 multipart processing for all requests.
        request.setAttribute("org.eclipse.jetty.multipartConfig", MULTIPART_CONFIG);
        request.setAttribute("org.eclipse.multipartConfig", MULTIPART_CONFIG);

        // Jetty 8.x does not populate getParameterMap() from multipart form fields without a
        // prior getParts() call (unlike 9.3+ where extractContentParameters() does this).
        // Pre-call getParts() for BODY_MULTIPART so the servlet can read form fields via
        // getParameterMap(). Skip for BODY_MULTIPART_REPEATED and BODY_MULTIPART_COMBINED,
        // which call getParts() themselves and rely on the first call triggering filenames.
        HttpServerTest.ServerEndpoint endpoint =
            HttpServerTest.ServerEndpoint.forPath(request.getRequestURI());
        if (endpoint == HttpServerTest.ServerEndpoint.BODY_MULTIPART) {
          try {
            request.getParts();
          } catch (IOException | ServletException ignored) {
          }
        }

        Jetty76Test.TestHandler.handleRequest(baseRequest, response);
        baseRequest.setHandled(true);
      } else {
        ((AbstractHandler) Jetty76Test.getErrorHandler())
            .handle(target, baseRequest, request, response);
      }
    }
  }
}

@EnabledIfSystemProperty(named = "test.dd.filenames", matches = ".+")
class Jetty8V0LatestDepForkedTest extends Jetty8LatestDepForkedTest
    implements TestingGenericHttpNamingConventions.ServerV0 {

  @Override
  public int version() {
    return 0;
  }

  @Override
  public String service() {
    return null;
  }

  @Override
  public String operation() {
    return "servlet.request";
  }
}

@EnabledIfSystemProperty(named = "test.dd.filenames", matches = ".+")
class Jetty8V1LatestDepForkedTest extends Jetty8LatestDepForkedTest
    implements TestingGenericHttpNamingConventions.ServerV1 {

  @Override
  public int version() {
    return 1;
  }

  @Override
  public String service() {
    return null;
  }

  @Override
  public String operation() {
    return "http.server.request";
  }
}
