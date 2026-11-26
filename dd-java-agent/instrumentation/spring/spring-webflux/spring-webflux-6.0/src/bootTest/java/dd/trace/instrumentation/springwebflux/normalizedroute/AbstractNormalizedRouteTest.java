package dd.trace.instrumentation.springwebflux.normalizedroute;

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
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs {@link NormalizedRouteApplication} on Netty under {@code spring.webflux.base-path=/base},
 * with AppSec forced active like the Spock {@code InstrumentationSpecification}, and returns the
 * tags of the server span of a request.
 */
@TestInstance(PER_CLASS)
abstract class AbstractNormalizedRouteTest extends AbstractInstrumentationTest {

  /** {@code spring.webflux.base-path}: part of the request path, not of {@code http.route}. */
  static final String BASE_PATH = "/base";

  private final OkHttpClient client = OkHttpUtils.client();
  private ConfigurableApplicationContext context;
  private boolean appSecActiveBefore;

  @BeforeAll
  void startApp() {
    SpringApplication app = new SpringApplication(NormalizedRouteApplication.class);
    app.setWebApplicationType(WebApplicationType.REACTIVE);
    Map<String, Object> props = new HashMap<>();
    props.put("server.port", 0);
    props.put("spring.webflux.base-path", BASE_PATH);
    app.setDefaultProperties(props);
    context = app.run();
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

  /** Sends {@code GET BASE_PATH + path}, expects a 200 and returns the tags of the server span. */
  Map<String, Object> serverSpanTags(String path) throws Exception {
    int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    Request request =
        new Request.Builder().url("http://localhost:" + port + BASE_PATH + path).get().build();
    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
    }
    writer.waitForTraces(1);
    DDSpan serverSpan = null;
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if ("netty.request".equals(span.getOperationName().toString())) {
          serverSpan = span;
        }
      }
    }
    assertNotNull(serverSpan, "netty.request span");
    return serverSpan.getTags();
  }
}
