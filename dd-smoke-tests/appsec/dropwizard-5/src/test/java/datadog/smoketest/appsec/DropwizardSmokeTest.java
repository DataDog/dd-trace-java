package datadog.smoketest.appsec;

import static datadog.smoketest.appsec.AppSecRules.DEFAULT_APPSEC_PROPERTIES;
import static datadog.smoketest.appsec.AppSecRules.writeMergedRules;
import static datadog.smoketest.backend.AgentBackend.testAgent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.appsec.api.blocking.BlockingException;
import datadog.smoketest.SmokeServerApp;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import datadog.trace.test.util.PollingConditions;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.tabletest.junit.TableTest;

/**
 * Reproduces a Dropwizard 5 stack (Jersey 3.1 on Jetty 12.1 ee10, with a newer jetty-server core
 * than the ee10 servlet layer) to verify AppSec blocking end to end, both in the request phase and
 * in the response phase.
 */
class DropwizardSmokeTest {
  private static final String BUILD_DIR =
      new File(System.getProperty("datadog.smoketest.builddir")).getAbsolutePath();

  /** Default ruleset with the test rules appended, written before the app is launched. */
  private static final String RULES_PATH =
      writeMergedRules(
          System.getProperty("datadog.smoketest.agent.shadowJar.path"),
          "appsec_custom_rules.json",
          new File(BUILD_DIR, "appsec_custom_rules.json"));

  @RegisterExtension
  static final SmokeServerApp app =
      SmokeServerApp.named("dropwizard-5")
          .jar(System.getProperty("datadog.smoketest.shadowJar.path"))
          .jvmArgs(DEFAULT_APPSEC_PROPERTIES)
          .jvmArgs("-Ddd.appsec.rules=" + RULES_PATH, "-Ddw.server.connector.port=${app.httpPort}")
          .workingDirectory(new File(BUILD_DIR))
          .backend(testAgent())
          .build();

  private static final JsonAdapter<Map<String, Object>> APPSEC_JSON =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private static final MediaType APPLICATION_JSON = MediaType.get("application/json");

  private final OkHttpClient client = new OkHttpClient();

  @Test
  void requestWithNoAttackIsNotBlocked() {
    HttpResult response = execute(get("/api/hello"));

    assertEquals(200, response.code);
    assertEquals("OK", response.body);
    DecodedSpan rootSpan = rootSpanFor("/api/hello");
    assertNull(rootSpan.getMeta().get("appsec.blocked"));
    assertEquals("200", rootSpan.getMeta().get("http.status_code"));
  }

  @Test
  void blockRequestBasedOnHeader() {
    HttpResult response =
        execute(
            request("/api/hello?scenario=header")
                .addHeader("User-Agent", "dd-test-scanner-log-block")
                .get()
                .build());

    assertBlocked(response, "/api/hello?scenario=header", "ua0-600-56x");
  }

  @TableTest({
    "Scenario                      | path                  ",
    "resource returning 404 status | /api/nfd-probe-status ",
    "resource throwing 404         | /api/nfd-probe-throw  ",
    "no matching Jersey resource   | /api/nfd-probe-nomatch",
    "path outside Jersey servlet   | /nfd-probe-outside    "
  })
  void blockOn404Response(String path) {
    HttpResult response = execute(get(path));

    assertBlocked(response, path, "__test_nfd_probe_block");
  }

  @Test
  void blockRequestBasedOnJsonBody() {
    HttpResult response =
        execute(
            request("/api/json")
                .post(RequestBody.create(APPLICATION_JSON, "{\"key\":\"dd-test-json-body-block\"}"))
                .build());

    assertBlocked(response, "/api/json", "__test_json_body_block");
  }

  @Test
  void raspBlocksOnSsrf() {
    HttpResult response = execute(get("/api/ssrf?domain=169.254.169.254"));

    DecodedSpan rootSpan =
        assertBlocked(response, "/api/ssrf?domain=169.254.169.254", "rasp-934-100");
    // ensure the block was propagated
    assertEquals(BlockingException.class.getName(), rootSpan.getMeta().get("error.type"));
  }

  @Test
  void blockRequestBasedOnUserId() {
    HttpResult response = execute(get("/api/user?id=user-to-block"));

    assertBlocked(response, "/api/user?id=user-to-block", "__test_user_block");
  }

  private static Request.Builder request(String pathAndQuery) {
    return new Request.Builder().url(app.url() + pathAndQuery);
  }

  private static Request get(String pathAndQuery) {
    return request(pathAndQuery).get().build();
  }

  private HttpResult execute(Request request) {
    try (Response response = this.client.newCall(request).execute()) {
      ResponseBody body = response.body();
      return new HttpResult(
          response.code(), response.header("Content-Type"), body == null ? "" : body.string());
    } catch (IOException e) {
      throw new UncheckedIOException(request.method() + " " + request.url() + " failed", e);
    }
  }

  /**
   * Waits for the root span of the request to {@code pathAndQuery}. Selecting by URL instead of
   * counting traces avoids picking up a late trace from the previous test.
   */
  private static DecodedSpan rootSpanFor(String pathAndQuery) {
    List<DecodedSpan> found = new ArrayList<>();
    new PollingConditions(30)
        .eventually(() -> {
          List<DecodedSpan> matching = new ArrayList<>();
          for (DecodedTrace trace : app.traces().getTraces()) {
            // The AppSec report is on the root span
            DecodedSpan root = Decoder.sortByStart(trace.getSpans()).get(0);
            if (pathAndQuery.equals(pathAndQuery(root.getMeta().get("http.url")))) {
              matching.add(root);
            }
          }
          assertEquals(1, matching.size(), "expected one root span for " + pathAndQuery);
          found.clear();
          found.add(matching.get(0));
        });
    return found.get(0);
  }

  private static String pathAndQuery(String url) {
    if (url == null) {
      return null;
    }
    URI uri = URI.create(url);
    return uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()
        ? uri.getRawPath() + "?" + uri.getRawQuery()
        : uri.getRawPath();
  }

  /** Asserts the client got the default blocking page and the root span records the block. */
  private static DecodedSpan assertBlocked(
      HttpResult response, String pathAndQuery, String ruleId) {
    assertEquals(403, response.code);
    assertNotNull(response.contentType, "Content-Type is not set");
    assertTrue(
        response.contentType.startsWith("application/json"),
        "unexpected Content-Type " + response.contentType);
    assertTrue(response.body.contains("\"title\":\"You've been blocked\""), response.body);
    assertTrue(response.body.contains("\"security_response_id\":"), response.body);

    DecodedSpan rootSpan = rootSpanFor(pathAndQuery);
    Map<String, String> meta = rootSpan.getMeta();
    assertEquals("true", meta.get("appsec.blocked"), "appsec.blocked is not set");
    assertEquals("403", meta.get("http.status_code"));
    String appsecJson = meta.get("_dd.appsec.json");
    assertNotNull(appsecJson, "_dd.appsec.json is not set");
    assertTrue(triggeredRuleIds(appsecJson).contains(ruleId), "trigger " + ruleId + " not found");
    return rootSpan;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> triggeredRuleIds(String appsecJson) {
    Map<String, Object> report;
    try {
      report = APPSEC_JSON.fromJson(appsecJson);
    } catch (IOException e) {
      throw new UncheckedIOException("Invalid _dd.appsec.json: " + appsecJson, e);
    }
    Object triggers = report == null ? null : report.get("triggers");
    if (!(triggers instanceof List)) {
      return Collections.emptyList();
    }
    List<Object> ruleIds = new ArrayList<>();
    for (Map<String, Object> trigger : (List<Map<String, Object>>) triggers) {
      Map<String, Object> rule = (Map<String, Object>) trigger.get("rule");
      if (rule != null) {
        ruleIds.add(rule.get("id"));
      }
    }
    return ruleIds;
  }

  /** Status, content type and body of a fully read response. */
  private static final class HttpResult {
    final int code;
    final String contentType;
    final String body;

    HttpResult(int code, String contentType, String body) {
      this.code = code;
      this.contentType = contentType;
      this.body = body;
    }
  }
}
