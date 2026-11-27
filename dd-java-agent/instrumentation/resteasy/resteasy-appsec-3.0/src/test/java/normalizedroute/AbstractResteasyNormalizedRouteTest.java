package normalizedroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.OkHttpUtils;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

/**
 * Serves {@link NormalizedRouteResource} with RESTEasy 3 in a Jetty servlet container ({@code /ctx}
 * context path, {@code /api} mapping prefix), with AppSec forced active like the Spock {@code
 * InstrumentationSpecification}, and returns the tags of the server span of a request.
 */
@TestInstance(PER_CLASS)
abstract class AbstractResteasyNormalizedRouteTest extends AbstractInstrumentationTest {

  static final String PREFIX = "/ctx/api";

  private final OkHttpClient client = OkHttpUtils.client();
  private Server server;
  private int port;
  private boolean appSecActiveBefore;

  @BeforeAll
  void startServer() throws Exception {
    server = new Server();
    SelectChannelConnector connector = new SelectChannelConnector();
    connector.setPort(0);
    server.addConnector(connector);
    ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/ctx");
    ServletHolder holder = new ServletHolder(new HttpServletDispatcher());
    holder.setInitParameter("resteasy.servlet.mapping.prefix", "/api");
    holder.setInitParameter("resteasy.resources", NormalizedRouteResource.class.getName());
    context.addServlet(holder, "/api/*");
    server.setHandler(context);
    server.start();
    port = connector.getLocalPort();
  }

  @AfterAll
  void stopServer() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  @BeforeEach
  void forceAppSecActive() {
    appSecActiveBefore = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSecActive() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActiveBefore;
  }

  /** Sends {@code GET PREFIX + path}, expects a 200 and returns the tags of the server span. */
  Map<String, Object> serverSpanTags(String path) throws Exception {
    return serverSpanTags(path, 200);
  }

  /** Sends {@code GET PREFIX + path}, expects {@code status} and returns the server span tags. */
  Map<String, Object> serverSpanTags(String path, int status) throws Exception {
    Request request =
        new Request.Builder().url("http://localhost:" + port + PREFIX + path).get().build();
    try (Response response = client.newCall(request).execute()) {
      assertEquals(status, response.code());
    }
    writer.waitForTraces(1);
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if (span.isLocalRootSpan()) {
          return span.getTags();
        }
      }
    }
    throw new AssertionError("no server span");
  }
}
