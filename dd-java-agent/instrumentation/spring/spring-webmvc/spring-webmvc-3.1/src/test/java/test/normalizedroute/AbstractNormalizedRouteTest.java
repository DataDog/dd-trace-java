package test.normalizedroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.OkHttpUtils;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.core.DDSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;

/**
 * Runs {@link NormalizedRouteAppConfig} on an embedded server, with AppSec forced active like the
 * Spock {@code InstrumentationSpecification}, and returns the tags of the server span of a request.
 */
@TestInstance(PER_CLASS)
abstract class AbstractNormalizedRouteTest extends AbstractInstrumentationTest {

  private final OkHttpClient client = OkHttpUtils.client();
  private EmbeddedWebApplicationContext context;
  private boolean appSecActiveBefore;

  /** Servlet context path, empty for the root context. */
  String contextPath() {
    return "";
  }

  /** DispatcherServlet path mapping ({@code /api} for {@code /api/*}), empty for {@code /}. */
  String servletPath() {
    return "";
  }

  @BeforeAll
  void startApp() {
    SpringApplication app = new SpringApplication(NormalizedRouteAppConfig.class);
    Map<String, Object> props = new HashMap<>();
    props.put("server.port", 0);
    if (!contextPath().isEmpty()) {
      props.put("server.context-path", contextPath());
    }
    if (!servletPath().isEmpty()) {
      props.put("server.servlet-path", servletPath());
    }
    app.setDefaultProperties(props);
    context = (EmbeddedWebApplicationContext) app.run();
  }

  @AfterAll
  void stopApp() {
    if (context != null) {
      context.close();
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

  /** Mount prefix of the app: context path plus servlet path. */
  String prefix() {
    return contextPath() + servletPath();
  }

  /** Sends {@code GET prefix + path}, expects a 200 and returns the tags of the server span. */
  Map<String, Object> serverSpanTags(String path) throws Exception {
    int port = context.getEmbeddedServletContainer().getPort();
    Request request =
        new Request.Builder().url("http://localhost:" + port + prefix() + path).get().build();
    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
    }
    writer.waitForTraces(1);
    DDSpan serverSpan = null;
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if ("servlet.request".equals(span.getOperationName().toString())) {
          serverSpan = span;
        }
      }
    }
    assertNotNull(serverSpan, "servlet.request span");
    return serverSpan.getTags();
  }
}
