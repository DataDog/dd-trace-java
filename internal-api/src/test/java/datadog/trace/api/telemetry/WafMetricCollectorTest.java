package datadog.trace.api.telemetry;

import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS;
import static datadog.trace.api.telemetry.LoginEvent.SIGN_UP;
import static datadog.trace.api.telemetry.LoginFramework.SPRING_SECURITY;
import static datadog.trace.api.telemetry.LoginVersion.V1;
import static datadog.trace.api.telemetry.LoginVersion.V2;
import static datadog.trace.api.telemetry.RuleType.SHELL_INJECTION;
import static datadog.trace.api.telemetry.RuleType.SQL_INJECTION;
import static datadog.trace.api.telemetry.WafMetricCollector.computeWafInputTruncatedIndex;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.api.telemetry.WafMetricCollector.AIGuardTruncationType;
import datadog.trace.api.telemetry.WafMetricCollector.AfterRequestRaspRuleSkipped;
import datadog.trace.api.telemetry.WafMetricCollector.ApiSecurityMissingRoute;
import datadog.trace.api.telemetry.WafMetricCollector.ApiSecurityRequestNoSchema;
import datadog.trace.api.telemetry.WafMetricCollector.ApiSecurityRequestSchema;
import datadog.trace.api.telemetry.WafMetricCollector.RaspError;
import datadog.trace.api.telemetry.WafMetricCollector.RaspRuleEval;
import datadog.trace.api.telemetry.WafMetricCollector.RaspRuleMatch;
import datadog.trace.api.telemetry.WafMetricCollector.RaspTimeout;
import datadog.trace.api.telemetry.WafMetricCollector.WafError;
import datadog.trace.api.telemetry.WafMetricCollector.WafErrorCode;
import datadog.trace.api.telemetry.WafMetricCollector.WafInitRawMetric;
import datadog.trace.api.telemetry.WafMetricCollector.WafMetric;
import datadog.trace.api.telemetry.WafMetricCollector.WafUpdatesRawMetric;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class WafMetricCollectorTest {

  static final int DD_WAF_RUN_INTERNAL_ERROR = WafErrorCode.INTERNAL_ERROR.getCode();
  static final int DD_WAF_RUN_INVALID_OBJECT_ERROR = WafErrorCode.INVALID_OBJECT.getCode();

  private static final int RAW_QUEUE_LIMIT = 1024;

  private WafMetricCollector collector;

  @BeforeEach
  void resetCollector() {
    collector = WafMetricCollector.get();
    // The collector is a singleton with process wide state, so flush anything left behind by
    // previously executed tests: prepareMetrics() moves the counters into the raw queue and
    // drain() empties it.
    for (int round = 0; round < 3; round++) {
      collector.prepareMetrics();
      collector.drain();
    }
  }

  @Test
  void noMetricsDrainsEmptyList() {
    collector.prepareMetrics();

    assertTrue(collector.drain().isEmpty());
  }

  @Test
  void putGetWafAndRaspMetrics() {
    collector.wafInit("waf_ver1", "rules.1", true);
    collector.wafUpdates("rules.2", true);
    collector.wafUpdates("rules.3", false);
    collector.raspRuleEval(SQL_INJECTION);
    collector.raspRuleEval(SQL_INJECTION);
    collector.raspRuleMatch(SQL_INJECTION, false);
    collector.raspRuleEval(SQL_INJECTION);
    collector.raspTimeout(SQL_INJECTION);
    collector.raspErrorCode(SHELL_INJECTION, DD_WAF_RUN_INTERNAL_ERROR);
    collector.wafErrorCode(DD_WAF_RUN_INTERNAL_ERROR);
    collector.raspErrorCode(SQL_INJECTION, DD_WAF_RUN_INVALID_OBJECT_ERROR);
    collector.wafErrorCode(DD_WAF_RUN_INVALID_OBJECT_ERROR);
    collector.raspRuleSkipped(SQL_INJECTION);

    collector.prepareMetrics();

    List<WafMetric> metrics = drainAsList();

    assertMetric(
        assertInstanceOf(WafInitRawMetric.class, metrics.get(0)),
        "waf.init",
        1,
        "waf_version:waf_ver1",
        "event_rules_version:rules.1",
        "success:true");

    assertMetric(
        assertInstanceOf(WafUpdatesRawMetric.class, metrics.get(1)),
        "waf.updates",
        1,
        "waf_version:waf_ver1",
        "event_rules_version:rules.2",
        "success:true");

    assertMetric(
        assertInstanceOf(WafUpdatesRawMetric.class, metrics.get(2)),
        "waf.updates",
        1,
        "waf_version:waf_ver1",
        "event_rules_version:rules.3",
        "success:false");

    assertMetric(
        assertInstanceOf(RaspRuleEval.class, metrics.get(3)),
        "rasp.rule.eval",
        3,
        "rule_type:sql_injection",
        "waf_version:waf_ver1");

    assertMetric(
        assertInstanceOf(RaspRuleMatch.class, metrics.get(4)),
        "rasp.rule.match",
        1,
        "rule_type:sql_injection",
        "waf_version:waf_ver1",
        "block:false");

    assertMetric(
        assertInstanceOf(RaspTimeout.class, metrics.get(5)),
        "rasp.timeout",
        1,
        "rule_type:sql_injection",
        "waf_version:waf_ver1");

    assertMetric(
        assertInstanceOf(RaspError.class, metrics.get(6)),
        "rasp.error",
        1,
        "rule_type:sql_injection",
        "waf_version:waf_ver1",
        "waf_error:" + DD_WAF_RUN_INVALID_OBJECT_ERROR);

    assertMetric(
        assertInstanceOf(RaspError.class, metrics.get(7)),
        "rasp.error",
        1,
        "waf_version:waf_ver1",
        "rule_type:command_injection",
        "rule_variant:shell",
        "event_rules_version:rules.3",
        "waf_error:" + DD_WAF_RUN_INTERNAL_ERROR);

    assertMetric(
        assertInstanceOf(WafError.class, metrics.get(8)),
        "waf.error",
        1,
        "waf_version:waf_ver1",
        "event_rules_version:rules.3",
        "waf_error:" + DD_WAF_RUN_INVALID_OBJECT_ERROR);

    assertMetric(
        assertInstanceOf(WafError.class, metrics.get(9)),
        "waf.error",
        1,
        "waf_version:waf_ver1",
        "event_rules_version:rules.3",
        "waf_error:" + DD_WAF_RUN_INTERNAL_ERROR);

    assertMetric(
        assertInstanceOf(AfterRequestRaspRuleSkipped.class, metrics.get(10)),
        "rasp.rule.skipped",
        1,
        "rule_type:sql_injection",
        "reason:after-request");
  }

  @Test
  void overflowingCollectorDoesNotCrash() {
    for (int i = 0; i <= RAW_QUEUE_LIMIT * 2; i++) {
      collector.wafInit("foo", "bar", true);
    }
    assertEquals(RAW_QUEUE_LIMIT, collector.drain().size());

    for (int i = 0; i <= RAW_QUEUE_LIMIT * 2; i++) {
      collector.wafUpdates("bar", true);
    }
    assertEquals(RAW_QUEUE_LIMIT, collector.drain().size());

    for (int round = 0; round < 3; round++) {
      for (int i = 0; i <= RAW_QUEUE_LIMIT * 2; i++) {
        collector.raspRuleEval(SQL_INJECTION);
        collector.prepareMetrics();
      }
      assertEquals(RAW_QUEUE_LIMIT, collector.drain().size());
    }
  }

  @Test
  void missingUserLoginEventMetric() throws InterruptedException {
    int loginSuccessCount = 6;
    int loginFailureCount = 3;
    int signupCount = 2;
    CountDownLatch latch = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(4);

    submitMissingUserLogins(executor, latch, LOGIN_SUCCESS, loginSuccessCount);
    submitMissingUserLogins(executor, latch, LOGIN_FAILURE, loginFailureCount);
    submitMissingUserLogins(executor, latch, SIGN_UP, signupCount);

    latch.countDown();
    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

    collector.prepareMetrics();
    List<WafMetric> metrics =
        metricsNamed(collector.drain(), "instrum.user_auth.missing_user_login");
    assertEquals(3, metrics.size());

    Map<String, Long> countsByEventType = new HashMap<>();
    for (WafMetric metric : metrics) {
      assertEquals("appsec", metric.namespace);
      assertEquals("count", metric.type);
      Map<String, String> tags = tagsAsMap(metric);
      assertEquals(SPRING_SECURITY.getTag(), tags.get("framework"));
      countsByEventType.put(tags.get("event_type"), metric.value.longValue());
    }

    Map<String, Long> expectedCounts = new HashMap<>();
    expectedCounts.put(LOGIN_SUCCESS.getTag(), (long) loginSuccessCount);
    expectedCounts.put(LOGIN_FAILURE.getTag(), (long) loginFailureCount);
    expectedCounts.put(SIGN_UP.getTag(), (long) signupCount);
    assertEquals(expectedCounts, countsByEventType);
  }

  @Test
  void missingUserIdEventMetric() throws InterruptedException {
    int count = 6;
    CountDownLatch latch = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(4);

    for (int i = 0; i < count; i++) {
      executor.submit(
          () -> {
            latch.await();
            collector.missingUserId(SPRING_SECURITY);
            return null;
          });
    }

    latch.countDown();
    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "instrum.user_auth.missing_user_id");
    assertEquals(1, metrics.size());

    WafMetric metric = metrics.get(0);
    assertMetric(
        metric,
        "instrum.user_auth.missing_user_id",
        count,
        "framework:" + SPRING_SECURITY.getTag(),
        "event_type:authenticated_request");
  }

  @EnumSource(
      value = RuleType.class,
      names = {"COMMAND_INJECTION", "SHELL_INJECTION"})
  @ParameterizedTest
  void raspMetricsPerRuleType(RuleType ruleType) {
    collector.wafInit("waf_ver1", "rules.1", true);
    collector.raspRuleEval(ruleType);
    collector.raspRuleEval(ruleType);
    collector.raspRuleMatch(ruleType, false);
    collector.raspRuleEval(ruleType);
    collector.raspTimeout(ruleType);
    collector.raspErrorCode(ruleType, DD_WAF_RUN_INTERNAL_ERROR);
    collector.wafErrorCode(DD_WAF_RUN_INTERNAL_ERROR);
    collector.raspRuleSkipped(ruleType);
    collector.prepareMetrics();

    List<WafMetric> metrics = drainAsList();

    assertMetric(
        assertInstanceOf(RaspRuleEval.class, metrics.get(1)),
        "rasp.rule.eval",
        3,
        "rule_type:command_injection",
        "rule_variant:" + ruleType.variant,
        "waf_version:waf_ver1",
        "event_rules_version:rules.1");

    assertMetric(
        assertInstanceOf(RaspRuleMatch.class, metrics.get(2)),
        "rasp.rule.match",
        1,
        "rule_type:command_injection",
        "rule_variant:" + ruleType.variant,
        "waf_version:waf_ver1",
        "event_rules_version:rules.1",
        "block:false");

    assertMetric(
        assertInstanceOf(RaspTimeout.class, metrics.get(3)),
        "rasp.timeout",
        1,
        "rule_type:command_injection",
        "rule_variant:" + ruleType.variant,
        "waf_version:waf_ver1",
        "event_rules_version:rules.1");

    assertMetric(
        assertInstanceOf(RaspError.class, metrics.get(4)),
        "rasp.error",
        1,
        "waf_version:waf_ver1",
        "rule_type:command_injection",
        "rule_variant:" + ruleType.variant,
        "event_rules_version:rules.1",
        "waf_error:" + DD_WAF_RUN_INTERNAL_ERROR);

    assertMetric(
        assertInstanceOf(WafError.class, metrics.get(5)),
        "waf.error",
        1,
        "waf_version:waf_ver1",
        "event_rules_version:rules.1",
        "waf_error:" + DD_WAF_RUN_INTERNAL_ERROR);

    assertMetric(
        assertInstanceOf(AfterRequestRaspRuleSkipped.class, metrics.get(6)),
        "rasp.rule.skipped",
        1,
        "rule_type:command_injection",
        "rule_variant:" + ruleType.variant,
        "reason:after-request");
  }

  @Test
  void loginEventMetrics() {
    collector.appSecSdkEvent(LOGIN_SUCCESS, V1);
    collector.appSecSdkEvent(LOGIN_FAILURE, V2);

    collector.prepareMetrics();
    List<WafMetric> sdkEvents = metricsNamed(collector.drain(), "sdk.event");
    assertEquals(2, sdkEvents.size());

    assertMetricWithOrderedTags(
        sdkEvents.get(0), "sdk.event", 1, "event_type:login_success", "sdk_version:v1");
    assertMetricWithOrderedTags(
        sdkEvents.get(1), "sdk.event", 1, "event_type:login_failure", "sdk_version:v2");
  }

  @MethodSource("wafRequestMetricsArguments")
  @ParameterizedTest
  void wafRequestMetrics(
      boolean triggered,
      boolean blocked,
      boolean wafError,
      boolean wafTimeout,
      boolean blockFailure,
      boolean rateLimited,
      boolean inputTruncated,
      boolean requestExcluded) {
    collector.wafInit("waf_ver1", "rules.1", true);

    collector.wafRequest(
        triggered,
        blocked,
        wafError,
        wafTimeout,
        blockFailure,
        rateLimited,
        inputTruncated,
        requestExcluded);

    collector.prepareMetrics();
    List<WafMetric> requestMetrics = metricsNamed(collector.drain(), "waf.requests");

    assertEquals(1, requestMetrics.size());
    assertMetricWithOrderedTags(
        requestMetrics.get(0),
        "waf.requests",
        1,
        "waf_version:waf_ver1",
        "event_rules_version:rules.1",
        "rule_triggered:" + triggered,
        "request_blocked:" + blocked,
        "waf_error:" + wafError,
        "waf_timeout:" + wafTimeout,
        "block_failure:" + blockFailure,
        "rate_limited:" + rateLimited,
        "input_truncated:" + inputTruncated,
        "request_excluded:" + (requestExcluded ? "full" : "none"));
  }

  static Stream<Arguments> wafRequestMetricsArguments() {
    return booleanCombinations(8);
  }

  @TableTest({
    "scenario                        | stringTooLong | listMapTooLarge | objectTooDeep",
    "no truncation                   | false         | false           | false        ",
    "string too long                 | true          | false           | false        ",
    "list or map too large           | false         | true            | false        ",
    "string and list or map          | true          | true            | false        ",
    "object too deep                 | false         | false           | true         ",
    "string and object too deep      | true          | false           | true         ",
    "list or map and object too deep | false         | true            | true         ",
    "all truncation reasons          | true          | true            | true         "
  })
  void wafInputTruncatedMetrics(
      boolean stringTooLong, boolean listMapTooLarge, boolean objectTooDeep) {
    int bitField = computeWafInputTruncatedIndex(stringTooLong, listMapTooLarge, objectTooDeep);

    collector.wafInputTruncated(stringTooLong, listMapTooLarge, objectTooDeep);

    collector.prepareMetrics();
    List<WafMetric> inputTruncatedMetrics = metricsNamed(collector.drain(), "waf.input_truncated");

    assertEquals(1, inputTruncatedMetrics.size());
    assertMetricWithOrderedTags(
        inputTruncatedMetrics.get(0), "waf.input_truncated", 1, "truncation_reason:" + bitField);
  }

  @Test
  void wafConfigErrorMetrics() {
    collector.wafInit("waf_ver1", "rules.1", true);
    collector.addWafConfigError(5);
    collector.addWafConfigError(3);
    collector.prepareMetrics();

    List<WafMetric> configErrorMetrics = metricsNamed(collector.drain(), "waf.config_errors");
    assertEquals(1, configErrorMetrics.size());

    assertMetric(
        configErrorMetrics.get(0),
        "waf.config_errors",
        8,
        "waf_version:waf_ver1",
        "event_rules_version:rules.1");
  }

  @TableTest({
    "scenario      | action | block",
    "allow blocked | ALLOW  | true ",
    "allow passed  | ALLOW  | false",
    "deny blocked  | DENY   | true ",
    "deny passed   | DENY   | false",
    "abort blocked | ABORT  | true ",
    "abort passed  | ABORT  | false"
  })
  void aiGuardRequest(AIGuard.Action action, boolean block) {
    collector.aiGuardRequest(action, block);

    collector.prepareMetrics();
    List<WafMetric> requestMetrics = metricsNamed(collector.drain(), "ai_guard.requests");
    assertEquals(1, requestMetrics.size());

    assertMetric(
        requestMetrics.get(0),
        "ai_guard.requests",
        1,
        "action:" + action.name(),
        "block:" + block,
        "error:false");
  }

  @Test
  void aiGuardError() {
    collector.aiGuardError();

    collector.prepareMetrics();
    List<WafMetric> requestMetrics = metricsNamed(collector.drain(), "ai_guard.requests");
    assertEquals(1, requestMetrics.size());

    assertMetric(requestMetrics.get(0), "ai_guard.requests", 1, "error:true");
  }

  @EnumSource(AIGuardTruncationType.class)
  @ParameterizedTest
  void aiGuardTruncated(AIGuardTruncationType type) {
    collector.aiGuardTruncated(type);

    collector.prepareMetrics();
    List<WafMetric> truncatedMetrics = metricsNamed(collector.drain(), "ai_guard.truncated");
    assertEquals(1, truncatedMetrics.size());

    assertMetric(truncatedMetrics.get(0), "ai_guard.truncated", 1, "type:" + type.tagValue);
  }

  @TableTest({
    "scenario                      | ruleType          | blocked",
    "sql injection blocked         | SQL_INJECTION     | true   ",
    "sql injection not blocked     | SQL_INJECTION     | false  ",
    "lfi blocked                   | LFI               | true   ",
    "lfi not blocked               | LFI               | false  ",
    "ssrf request blocked          | SSRF_REQUEST      | true   ",
    "ssrf request not blocked      | SSRF_REQUEST      | false  ",
    "ssrf response blocked         | SSRF_RESPONSE     | true   ",
    "ssrf response not blocked     | SSRF_RESPONSE     | false  ",
    "shell injection blocked       | SHELL_INJECTION   | true   ",
    "shell injection not blocked   | SHELL_INJECTION   | false  ",
    "command injection blocked     | COMMAND_INJECTION | true   ",
    "command injection not blocked | COMMAND_INJECTION | false  "
  })
  void raspRuleMatchBlockTag(RuleType ruleType, boolean blocked) {
    collector.wafInit("waf_ver1", "rules.1", true);

    collector.raspRuleMatch(ruleType, blocked);

    collector.prepareMetrics();
    List<WafMetric> matchMetrics = metricsNamed(collector.drain(), "rasp.rule.match");
    assertEquals(1, matchMetrics.size());

    String[] expectedTags =
        ruleType.variant != null
            ? new String[] {
              "rule_type:" + ruleType.type,
              "rule_variant:" + ruleType.variant,
              "waf_version:waf_ver1",
              "event_rules_version:rules.1",
              "block:" + blocked
            }
            : new String[] {
              "rule_type:" + ruleType.type, "waf_version:waf_ver1", "block:" + blocked
            };
    assertMetric(matchMetrics.get(0), "rasp.rule.match", 1, expectedTags);
  }

  @Test
  void raspRuleMatchDrainsBlockedAndNonBlockedAsSeparateMetrics() {
    collector.wafInit("waf_ver1", "rules.1", true);

    collector.raspRuleMatch(SQL_INJECTION, true);
    collector.raspRuleMatch(SQL_INJECTION, false);

    collector.prepareMetrics();
    List<WafMetric> matchMetrics = metricsNamed(collector.drain(), "rasp.rule.match");

    assertEquals(2, matchMetrics.size());
    assertEquals(1, metricWithTag(matchMetrics, "block:true").value.longValue());
    assertEquals(1, metricWithTag(matchMetrics, "block:false").value.longValue());
  }

  @Test
  void apiSecurityMissingRouteMetric() {
    collector.apiSecurityMissingRoute("netty");

    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "api_security.missing_route");
    assertEquals(1, metrics.size());

    assertMetric(
        assertInstanceOf(ApiSecurityMissingRoute.class, metrics.get(0)),
        "api_security.missing_route",
        1,
        "framework:netty");
  }

  @Test
  void apiSecurityRequestSchemaMetric() {
    collector.apiSecurityRequestSchema("netty");

    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "api_security.request.schema");
    assertEquals(1, metrics.size());

    assertMetric(
        assertInstanceOf(ApiSecurityRequestSchema.class, metrics.get(0)),
        "api_security.request.schema",
        1,
        "framework:netty");
  }

  @Test
  void apiSecurityRequestNoSchemaMetric() {
    collector.apiSecurityRequestNoSchema("netty");

    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "api_security.request.no_schema");
    assertEquals(1, metrics.size());

    assertMetric(
        assertInstanceOf(ApiSecurityRequestNoSchema.class, metrics.get(0)),
        "api_security.request.no_schema",
        1,
        "framework:netty");
  }

  @TableTest({
    "scenario         | framework | expectedFramework",
    "null framework   |           | unknown          ",
    "empty framework  | ''        | unknown          ",
    "blank framework  | '   '     | unknown          ",
    "normal framework | netty     | netty            "
  })
  void normalizeFramework(String framework, String expectedFramework) {
    assertEquals(expectedFramework, WafMetricCollector.normalizeFramework(framework));
  }

  private void submitMissingUserLogins(
      ExecutorService executor, CountDownLatch latch, LoginEvent event, int times) {
    for (int i = 0; i < times; i++) {
      executor.submit(
          () -> {
            latch.await();
            collector.missingUserLogin(SPRING_SECURITY, event);
            return null;
          });
    }
  }

  private List<WafMetric> drainAsList() {
    return new ArrayList<>(collector.drain());
  }

  private static List<WafMetric> metricsNamed(
      Collection<WafMetric> metrics, String expectedMetricName) {
    return metrics.stream()
        .filter(metric -> expectedMetricName.equals(metric.metricName))
        .collect(toList());
  }

  private static WafMetric metricWithTag(List<WafMetric> metrics, String tag) {
    return metrics.stream()
        .filter(metric -> metric.tags.contains(tag))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No metric found with tag " + tag));
  }

  private static Map<String, String> tagsAsMap(WafMetric metric) {
    Map<String, String> tags = new HashMap<>();
    for (String tag : metric.tags) {
      String[] parts = tag.split(":", 2);
      tags.put(parts[0], parts[1]);
    }
    return tags;
  }

  /** Asserts the metric shape, comparing tags as an unordered set. */
  private static void assertMetric(
      WafMetric metric, String expectedMetricName, long expectedValue, String... expectedTags) {
    assertEquals("count", metric.type);
    assertEquals("appsec", metric.namespace);
    assertEquals(expectedMetricName, metric.metricName);
    assertEquals(expectedValue, metric.value.longValue());
    assertEquals(new HashSet<>(asList(expectedTags)), new HashSet<>(metric.tags));
  }

  /** Asserts the metric shape, comparing tags in their declaration order. */
  private static void assertMetricWithOrderedTags(
      WafMetric metric, String expectedMetricName, long expectedValue, String... expectedTags) {
    assertEquals("count", metric.type);
    assertEquals("appsec", metric.namespace);
    assertEquals(expectedMetricName, metric.metricName);
    assertEquals(expectedValue, metric.value.longValue());
    assertEquals(asList(expectedTags), metric.tags);
  }

  /** Generates all combinations of {@code size} boolean values. */
  private static Stream<Arguments> booleanCombinations(int size) {
    return IntStream.range(0, 1 << size)
        .mapToObj(
            mask -> {
              Object[] combination = new Object[size];
              for (int bit = 0; bit < size; bit++) {
                combination[bit] = ((mask >> bit) & 1) == 1;
              }
              return arguments(combination);
            });
  }
}
