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
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import ratpack.handling.Handler;
import ratpack.test.embed.EmbeddedApp;

/**
 * Runs an embedded Ratpack app with the route shapes that need presence logic in the normalized
 * route, with AppSec forced active like the Spock {@code InstrumentationSpecification}, and returns
 * the tags of the {@code netty.request} and {@code ratpack.handler} spans of a request.
 */
@TestInstance(PER_CLASS)
abstract class AbstractNormalizedRouteTest extends AbstractInstrumentationTest {

  static final String NETTY_SPAN = "netty.request";
  static final String RATPACK_SPAN = "ratpack.handler";

  private static final Handler OK = ctx -> ctx.render("ok");

  private final OkHttpClient client = OkHttpUtils.client();
  private EmbeddedApp app;
  private boolean appSecActiveBefore;

  @BeforeAll
  void startApp() {
    app =
        EmbeddedApp.fromHandlers(chain ->
            chain
                .get("api_security/optional-segment/:id/:tab?", OK)
                .get("api_security/optional-chain/:x?/:y?", OK)
                .prefix("api_security/catch-all", c -> c.all(OK))
                .get("api_security/encoded/caf%C3%A9", OK)
                .prefix("users", c -> c.get(OK).get(":id", OK))
                .prefix("t/:tenant", c -> c.get("items/:id", OK))
                .get("regex/:id:\\d+", OK)
                .get("unnamed/::\\d+", OK)
                .prefix("pfx/::\\d+", c -> c.all(OK))
                .get("files/:name.json", OK)
                .get("fmt/:f?.json", OK)
                .prefix(
                    "err/:tenant",
                    c ->
                        c.get(
                                "boom/:id",
                                ctx -> {
                                  throw new RuntimeException("boom");
                                })
                            .get("bad/:id", ctx -> ctx.clientError(400)))
                .prefix("dup/:id", c -> c.get(":id?", OK))
                .get("", OK));
    // starts the server
    app.getAddress();
  }

  /** The shared app with the routes declared in {@link #startApp()}. */
  EmbeddedApp app() {
    return app;
  }

  @AfterAll
  void stopApp() {
    if (app != null) {
      app.close();
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

  /** Sends {@code GET path}, expects a 200 and returns the span tags by operation name. */
  Map<String, Map<String, Object>> spanTags(String path) throws Exception {
    return spanTags(path, 200);
  }

  /** Sends {@code GET path}, expects {@code status} and returns the span tags by operation name. */
  Map<String, Map<String, Object>> spanTags(String path, int status) throws Exception {
    return spanTags(app, "GET", path, status);
  }

  /**
   * Sends {@code method path} to {@code target}, expects {@code status} and returns the tags of the
   * {@code netty.request} and {@code ratpack.handler} spans by operation name. The URL is parsed
   * from the literal string so empty segments and percent-encoded octets reach the server
   * unchanged.
   */
  Map<String, Map<String, Object>> spanTags(
      EmbeddedApp target, String method, String path, int status) throws Exception {
    String base = target.getAddress().toString();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    HttpUrl url = HttpUrl.parse(base + path);
    assertNotNull(url, "url");
    assertEquals(path, url.encodedPath(), "the client must not normalize the path");
    RequestBody body = "GET".equals(method) ? null : RequestBody.create(null, new byte[0]);
    Request request = new Request.Builder().url(url).method(method, body).build();
    try (Response response = client.newCall(request).execute()) {
      assertEquals(status, response.code());
    }
    writer.waitForTraces(1);
    Map<String, Map<String, Object>> tags = new HashMap<>();
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        String name = span.getOperationName().toString();
        if (NETTY_SPAN.equals(name) || RATPACK_SPAN.equals(name)) {
          tags.put(name, span.getTags());
        }
      }
    }
    assertNotNull(tags.get(NETTY_SPAN), NETTY_SPAN + " span");
    assertNotNull(tags.get(RATPACK_SPAN), RATPACK_SPAN + " span");
    return tags;
  }
}
