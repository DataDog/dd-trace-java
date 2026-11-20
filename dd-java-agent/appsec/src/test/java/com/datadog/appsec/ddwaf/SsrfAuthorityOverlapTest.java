package com.datadog.appsec.ddwaf;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafBuilder;
import com.datadog.ddwaf.WafContext;
import com.datadog.ddwaf.WafHandle;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.tabletest.junit.TableTest;

/**
 * Regression test for the SSRF detector with the bundled rules ({@code rasp-934-100}). It
 * reproduces the shape of libddwaf#522 with a synthetic payload: a user input that injects the
 * authority and overlaps the URL scheme. The overlap cases need libsqreen >= 17.7.0 (libddwaf
 * 2.2.1); older versions skip the parameter and do not match.
 */
class SsrfAuthorityOverlapTest {
  private static final String SSRF_RULE = "rasp-934-100";
  private static final Waf.Limits LIMITS =
      new Waf.Limits(20, 256, 4096, ((long) Integer.MAX_VALUE) * 1000, Integer.MAX_VALUE);
  private static final Type STRING_OBJECT_MAP =
      Types.newParameterizedType(Map.class, String.class, Object.class);
  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<Map<String, Object>> CONFIG_ADAPTER =
      MOSHI.adapter(STRING_OBJECT_MAP);
  private static final JsonAdapter<List<Map<String, Object>>> EVENTS_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(List.class, STRING_OBJECT_MAP));

  private WafBuilder builder;
  private WafHandle handle;

  @BeforeEach
  void setup() throws Exception {
    assertTrue(WafInitialization.ONLINE);
    builder = new WafBuilder();
    try (InputStream stream = getClass().getResourceAsStream("/default_config.json")) {
      builder.addOrUpdateConfig(
          "ASM_DD/default", CONFIG_ADAPTER.fromJson(Okio.buffer(Okio.source(stream))));
    }
    handle = builder.buildWafHandleInstance();
  }

  @AfterEach
  void cleanup() {
    if (handle != null) {
      handle.close();
    }
    if (builder != null) {
      builder.close();
    }
  }

  @TableTest({
    "scenario                   | queryKey | queryValue                                 | outboundUrl                                | expectMatch",
    "scheme overlap             | url      | 'http://169.254.169.254'                   | 'http://169.254.169.254/latest/meta-data/' | true       ",
    "scheme overlap with port   | url      | 'http://169.254.169.254:80'                | 'http://169.254.169.254:80/latest/'        | true       ",
    "host only inside authority | domain   | '169.254.169.254'                          | 'http://169.254.169.254/latest/meta-data/' | true       ",
    "input equals full url      | url      | 'http://169.254.169.254/latest/meta-data/' | 'http://169.254.169.254/latest/meta-data/' | false      "
  })
  void ssrfDetection(String queryKey, String queryValue, String outboundUrl, boolean expectMatch)
      throws Exception {
    try (WafContext context = new WafContext(handle)) {
      // may fire non-RASP sqr-000-* rules: not asserted
      context.run(
          singletonMap("server.request.query", singletonMap(queryKey, singletonList(queryValue))),
          LIMITS,
          null);

      Waf.ResultWithData result =
          context.run(singletonMap("server.io.net.url", outboundUrl), LIMITS, null);

      Set<String> ruleIds = ruleIds(result.data);
      assertEquals(
          expectMatch,
          ruleIds.contains(SSRF_RULE),
          "rule ids on server.io.net.url run: " + ruleIds + " (result " + result.result + ")");
    }
  }

  private static Set<String> ruleIds(String data) throws Exception {
    Set<String> ids = new LinkedHashSet<>();
    if (data == null || data.isEmpty()) {
      return ids;
    }
    // the WAF result data is a JSON array of events (same shape WAFModule parses)
    List<Map<String, Object>> events = EVENTS_ADAPTER.fromJson(data);
    for (Map<String, Object> event : events) {
      Map<?, ?> rule = (Map<?, ?>) event.get("rule");
      ids.add(String.valueOf(rule.get("id")));
    }
    return ids;
  }
}
