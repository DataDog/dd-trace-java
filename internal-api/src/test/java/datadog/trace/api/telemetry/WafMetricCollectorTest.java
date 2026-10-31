package datadog.trace.api.telemetry;

import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS;
import static datadog.trace.api.telemetry.LoginEvent.SIGN_UP;
import static datadog.trace.api.telemetry.LoginFramework.SPRING_SECURITY;
import static datadog.trace.api.telemetry.LoginVersion.V1;
import static datadog.trace.api.telemetry.LoginVersion.V2;
import static datadog.trace.api.telemetry.RuleType.SQL_INJECTION;
import static datadog.trace.api.telemetry.WafMetricCollector.AIGuardRedaction.NOT_APPLIED;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.api.telemetry.WafMetricCollector.AIGuardError;
import datadog.trace.api.telemetry.WafMetricCollector.AIGuardRedaction;
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
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WafMetricCollectorTest extends DDJavaSpecification {

  /**
   * Call-path tags every ai_guard metric carries. Both are constant while a direct SDK call is the
   * only way to reach an evaluation in this tracer.
   */
  private static final List<String> CALL_PATH = asList("source:sdk", "integration:none");

  private static final int DD_WAF_RUN_INTERNAL_ERROR = WafErrorCode.INTERNAL_ERROR.getCode();
  private static final int DD_WAF_RUN_INVALID_OBJECT_ERROR = WafErrorCode.INVALID_OBJECT.getCode();

  private final WafMetricCollector collector = WafMetricCollector.get();

  /** Flushes metrics left over by previous tests, as the collector state is static. */
  @BeforeEach
  void drainPendingMetrics() {
    do {
      collector.prepareMetrics();
    } while (!collector.drain().isEmpty());
  }

  @Test
  void noMetricsDrainEmptyList() {
    // when
    collector.prepareMetrics();

    // then
    assertTrue(collector.drain().isEmpty());
  }

  @Test
  void putGetWafRaspMetrics() {
    // when
    collector.wafInit("waf_ver1", "rules.1", true);
    collector.wafUpdates("rules.2", true);
    collector.wafUpdates("rules.3", false);
    collector.raspRuleEval(SQL_INJECTION);
    collector.raspRuleEval(SQL_INJECTION);
    collector.raspRuleMatch(SQL_INJECTION, false);
    collector.raspRuleEval(SQL_INJECTION);
    collector.raspTimeout(SQL_INJECTION);
    collector.raspErrorCode(RuleType.SHELL_INJECTION, DD_WAF_RUN_INTERNAL_ERROR);
    collector.wafErrorCode(DD_WAF_RUN_INTERNAL_ERROR);
    collector.raspErrorCode(SQL_INJECTION, DD_WAF_RUN_INVALID_OBJECT_ERROR);
    collector.wafErrorCode(DD_WAF_RUN_INVALID_OBJECT_ERROR);
    collector.raspRuleSkipped(SQL_INJECTION);

    collector.prepareMetrics();

    // then
    List<WafMetric> metrics = new ArrayList<>(collector.drain());

    assertAppSecCount(
        WafInitRawMetric.class,
        metrics.get(0),
        "waf.init",
        1,
        tagSet("waf_version:waf_ver1", "event_rules_version:rules.1", "success:true"));

    assertAppSecCount(
        WafUpdatesRawMetric.class,
        metrics.get(1),
        "waf.updates",
        1,
        tagSet("waf_version:waf_ver1", "event_rules_version:rules.2", "success:true"));

    assertAppSecCount(
        WafUpdatesRawMetric.class,
        metrics.get(2),
        "waf.updates",
        1,
        tagSet("waf_version:waf_ver1", "event_rules_version:rules.3", "success:false"));

    assertAppSecCount(
        RaspRuleEval.class,
        metrics.get(3),
        "rasp.rule.eval",
        3,
        tagSet("rule_type:sql_injection", "waf_version:waf_ver1"));

    assertAppSecCount(
        RaspRuleMatch.class,
        metrics.get(4),
        "rasp.rule.match",
        1,
        tagSet("rule_type:sql_injection", "waf_version:waf_ver1", "block:false"));

    assertAppSecCount(
        RaspTimeout.class,
        metrics.get(5),
        "rasp.timeout",
        1,
        tagSet("rule_type:sql_injection", "waf_version:waf_ver1"));

    assertAppSecCount(
        RaspError.class,
        metrics.get(6),
        "rasp.error",
        1,
        tagSet(
            "rule_type:sql_injection",
            "waf_version:waf_ver1",
            "waf_error:" + DD_WAF_RUN_INVALID_OBJECT_ERROR));

    assertAppSecCount(
        RaspError.class,
        metrics.get(7),
        "rasp.error",
        1,
        tagSet(
            "waf_version:waf_ver1",
            "rule_type:command_injection",
            "rule_variant:shell",
            "event_rules_version:rules.3",
            "waf_error:" + DD_WAF_RUN_INTERNAL_ERROR));

    assertAppSecCount(
        WafError.class,
        metrics.get(8),
        "waf.error",
        1,
        tagSet(
            "waf_version:waf_ver1",
            "event_rules_version:rules.3",
            "waf_error:" + DD_WAF_RUN_INVALID_OBJECT_ERROR));

    assertAppSecCount(
        WafError.class,
        metrics.get(9),
        "waf.error",
        1,
        tagSet(
            "waf_version:waf_ver1",
            "event_rules_version:rules.3",
            "waf_error:" + DD_WAF_RUN_INTERNAL_ERROR));

    assertAppSecCount(
        AfterRequestRaspRuleSkipped.class,
        metrics.get(10),
        "rasp.rule.skipped",
        1,
        tagSet("rule_type:sql_injection", "reason:after-request"));
  }

  @Test
  void overflowingWafMetricCollectorDoesNotCrash() {
    // given
    int limit = 1024;

    // when
    assertDoesNotThrow(() -> {
      for (int index = 0; index <= limit * 2; index++) {
        collector.wafInit("foo", "bar", true);
      }
    });

    // then
    assertEquals(limit, collector.drain().size());

    // when
    assertDoesNotThrow(() -> {
      for (int index = 0; index <= limit * 2; index++) {
        collector.wafUpdates("bar", true);
      }
    });

    // then
    assertEquals(limit, collector.drain().size());

    // when
    assertDoesNotThrow(() -> raspRuleEvalAndPrepare(limit));

    // then
    assertEquals(limit, collector.drain().size());

    // when
    assertDoesNotThrow(() -> raspRuleEvalAndPrepare(limit));

    // then
    assertEquals(limit, collector.drain().size());

    // when
    assertDoesNotThrow(() -> raspRuleEvalAndPrepare(limit));

    // then
    assertEquals(limit, collector.drain().size());
  }

  @Test
  void testMissingUserLoginEventMetric() throws InterruptedException {
    // given
    int loginSuccessCount = 6;
    int loginFailureCount = 3;
    int signupCount = 2;
    CountDownLatch latch = new CountDownLatch(1);
    ExecutorService executors = Executors.newFixedThreadPool(4);

    // when
    submitMissingUserLogin(executors, latch, LOGIN_SUCCESS, loginSuccessCount);
    submitMissingUserLogin(executors, latch, LOGIN_FAILURE, loginFailureCount);
    submitMissingUserLogin(executors, latch, SIGN_UP, signupCount);

    latch.countDown();
    executors.shutdown();
    boolean finished = executors.awaitTermination(5, SECONDS);

    // then
    assertTrue(finished);
    collector.prepareMetrics();
    List<WafMetric> metrics =
        metricsNamed(collector.drain(), "instrum.user_auth.missing_user_login");
    assertEquals(3, metrics.size());
    Map<String, Long> expectedCounts = new HashMap<>();
    expectedCounts.put(LOGIN_SUCCESS.getTag(), (long) loginSuccessCount);
    expectedCounts.put(LOGIN_FAILURE.getTag(), (long) loginFailureCount);
    expectedCounts.put(SIGN_UP.getTag(), (long) signupCount);
    for (WafMetric metric : metrics) {
      assertEquals("appsec", metric.namespace);
      assertEquals("count", metric.type);
      Map<String, String> tags = tagMap(metric);
      assertEquals(SPRING_SECURITY.getTag(), tags.get("framework"));
      String eventType = tags.get("event_type");
      Long expectedCount = expectedCounts.get(eventType);
      assertNotNull(expectedCount, "Invalid event_type " + eventType);
      assertEquals(expectedCount.longValue(), metric.value.longValue());
    }
  }

  @Test
  void testMissingUserIdEventMetric() throws InterruptedException {
    // given
    int count = 6;
    CountDownLatch latch = new CountDownLatch(1);
    ExecutorService executors = Executors.newFixedThreadPool(4);

    // when
    for (int index = 0; index < count; index++) {
      executors.submit(() -> {
        latch.await();
        collector.missingUserId(SPRING_SECURITY);
        return null;
      });
    }

    latch.countDown();
    executors.shutdown();
    boolean finished = executors.awaitTermination(5, SECONDS);

    // then
    assertTrue(finished);
    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "instrum.user_auth.missing_user_id");
    assertEquals(1, metrics.size());
    for (WafMetric metric : metrics) {
      assertEquals("appsec", metric.namespace);
      assertEquals("count", metric.type);
      assertEquals(count, metric.value.longValue());
      Map<String, String> tags = tagMap(metric);
      assertEquals(SPRING_SECURITY.getTag(), tags.get("framework"));
      assertEquals("authenticated_request", tags.get("event_type"));
    }
  }

  @TableTest({
    "scenario          | ruleType         ",
    "command injection | COMMAND_INJECTION",
    "shell injection   | SHELL_INJECTION  "
  })
  void testRaspMetrics(RuleType ruleType) {
    // when
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

    // then
    List<WafMetric> metrics = new ArrayList<>(collector.drain());

    assertAppSecCount(
        RaspRuleEval.class,
        metrics.get(1),
        "rasp.rule.eval",
        3,
        tagSet(
            "rule_type:command_injection",
            "rule_variant:" + ruleType.variant,
            "waf_version:waf_ver1",
            "event_rules_version:rules.1"));

    assertAppSecCount(
        RaspRuleMatch.class,
        metrics.get(2),
        "rasp.rule.match",
        1,
        tagSet(
            "rule_type:command_injection",
            "rule_variant:" + ruleType.variant,
            "waf_version:waf_ver1",
            "event_rules_version:rules.1",
            "block:false"));

    assertAppSecCount(
        RaspTimeout.class,
        metrics.get(3),
        "rasp.timeout",
        1,
        tagSet(
            "rule_type:command_injection",
            "rule_variant:" + ruleType.variant,
            "waf_version:waf_ver1",
            "event_rules_version:rules.1"));

    assertAppSecCount(
        RaspError.class,
        metrics.get(4),
        "rasp.error",
        1,
        tagSet(
            "waf_version:waf_ver1",
            "rule_type:command_injection",
            "rule_variant:" + ruleType.variant,
            "event_rules_version:rules.1",
            "waf_error:" + DD_WAF_RUN_INTERNAL_ERROR));

    assertAppSecCount(
        WafError.class,
        metrics.get(5),
        "waf.error",
        1,
        tagSet(
            "waf_version:waf_ver1",
            "event_rules_version:rules.1",
            "waf_error:" + DD_WAF_RUN_INTERNAL_ERROR));

    assertAppSecCount(
        AfterRequestRaspRuleSkipped.class,
        metrics.get(6),
        "rasp.rule.skipped",
        1,
        tagSet(
            "rule_type:command_injection",
            "rule_variant:" + ruleType.variant,
            "reason:after-request"));
  }

  @Test
  void testLoginEventMetrics() {
    // when
    collector.appSecSdkEvent(LOGIN_SUCCESS, V1);
    collector.appSecSdkEvent(LOGIN_FAILURE, V2);

    // then
    collector.prepareMetrics();
    List<WafMetric> sdkEvents = metricsNamed(collector.drain(), "sdk.event");

    WafMetric loginSuccess = sdkEvents.get(0);
    assertEquals("count", loginSuccess.type);
    assertEquals(1, loginSuccess.value.longValue());
    assertEquals("appsec", loginSuccess.namespace);
    assertEquals("sdk.event", loginSuccess.metricName);
    assertEquals(asList("event_type:login_success", "sdk_version:v1"), loginSuccess.tags);

    WafMetric loginFailure = sdkEvents.get(1);
    assertEquals("count", loginFailure.type);
    assertEquals(1, loginFailure.value.longValue());
    assertEquals("appsec", loginFailure.namespace);
    assertEquals("sdk.event", loginFailure.metricName);
    assertEquals(asList("event_type:login_failure", "sdk_version:v2"), loginFailure.tags);
  }

  @ParameterizedTest
  @MethodSource("testWafRequestMetricsArguments")
  void testWafRequestMetrics(
      boolean triggered,
      boolean blocked,
      boolean wafError,
      boolean wafTimeout,
      boolean blockFailure,
      boolean rateLimited,
      boolean inputTruncated,
      boolean requestExcluded) {
    // given
    collector.wafInit("waf_ver1", "rules.1", true);

    // when
    collector.wafRequest(
        triggered,
        blocked,
        wafError,
        wafTimeout,
        blockFailure,
        rateLimited,
        inputTruncated,
        requestExcluded);

    // then
    collector.prepareMetrics();
    List<WafMetric> requestMetrics = metricsNamed(collector.drain(), "waf.requests");

    assertEquals(1, requestMetrics.size());
    WafMetric metric = requestMetrics.get(0);
    assertEquals("count", metric.type);
    assertEquals("waf.requests", metric.metricName);
    assertEquals("appsec", metric.namespace);
    assertEquals(1, metric.value.longValue());
    assertEquals(
        asList(
            "waf_version:waf_ver1",
            "event_rules_version:rules.1",
            "rule_triggered:" + triggered,
            "request_blocked:" + blocked,
            "waf_error:" + wafError,
            "waf_timeout:" + wafTimeout,
            "block_failure:" + blockFailure,
            "rate_limited:" + rateLimited,
            "input_truncated:" + inputTruncated,
            "request_excluded:" + (requestExcluded ? "full" : "none")),
        metric.tags);
  }

  static Stream<Arguments> testWafRequestMetricsArguments() {
    return allBooleanCombinations(8);
  }

  @ParameterizedTest
  @MethodSource("testWafInputTruncatedMetricsArguments")
  void testWafInputTruncatedMetrics(
      boolean stringTooLong, boolean listMapTooLarge, boolean objectTooDeep) {
    // given
    int bitField =
        WafMetricCollector.computeWafInputTruncatedIndex(
            stringTooLong, listMapTooLarge, objectTooDeep);

    // when
    collector.wafInputTruncated(stringTooLong, listMapTooLarge, objectTooDeep);

    // then
    collector.prepareMetrics();
    List<WafMetric> inputTruncatedMetrics = metricsNamed(collector.drain(), "waf.input_truncated");

    WafMetric metric = inputTruncatedMetrics.get(0);
    assertEquals("count", metric.type);
    assertEquals("waf.input_truncated", metric.metricName);
    assertEquals("appsec", metric.namespace);
    assertEquals(singletonList("truncation_reason:" + bitField), metric.tags);
  }

  static Stream<Arguments> testWafInputTruncatedMetricsArguments() {
    return allBooleanCombinations(3);
  }

  @Test
  void testWafConfigErrorMetrics() {
    // when
    collector.wafInit("waf_ver1", "rules.1", true);
    collector.addWafConfigError(5);
    collector.addWafConfigError(3);
    collector.prepareMetrics();

    // then
    List<WafMetric> configErrorMetrics = metricsNamed(collector.drain(), "waf.config_errors");

    WafMetric metric = configErrorMetrics.get(0);
    assertEquals("count", metric.type);
    assertEquals("waf.config_errors", metric.metricName);
    assertEquals("appsec", metric.namespace);
    assertEquals(8, metric.value.longValue());
    assertEquals(
        tagSet("waf_version:waf_ver1", "event_rules_version:rules.1"), new HashSet<>(metric.tags));
  }

  @TableTest({
    "scenario     | action | block",
    "allow, block | ALLOW  | true ",
    "allow        | ALLOW  | false",
    "deny, block  | DENY   | true ",
    "deny         | DENY   | false",
    "abort, block | ABORT  | true ",
    "abort        | ABORT  | false"
  })
  void testAiGuardRequest(AIGuard.Action action, boolean block) {
    // when
    collector.aiGuardRequest(action, block, NOT_APPLIED);

    // then
    collector.prepareMetrics();
    List<WafMetric> requestMetrics = metricsNamed(collector.drain(), "requests");

    WafMetric metric = requestMetrics.get(0);
    assertEquals("count", metric.type);
    assertEquals("requests", metric.metricName);
    assertEquals("ai_guard", metric.namespace);
    assertEquals(1, metric.value.longValue());
    assertEquals(
        withCallPath(
            asList("action:" + action.name(), "block:" + block, "error:false", "redacted:false")),
        new HashSet<>(metric.tags));
  }

  @TableTest({
    "scenario    | redaction   | expectedTags                                                    ",
    "applied     | APPLIED     | ['action:ALLOW', 'block:false', 'error:false', 'redacted:true'] ",
    "not applied | NOT_APPLIED | ['action:ALLOW', 'block:false', 'error:false', 'redacted:false']",
    "// the kill switch reports no redacted tag at all                                           ",
    "disabled    | DISABLED    | ['action:ALLOW', 'block:false', 'error:false']                  "
  })
  void testAiGuardRedactionTelemetryTag(AIGuardRedaction redaction, List<String> expectedTags) {
    // when
    collector.aiGuardRequest(ALLOW, false, redaction);

    // then
    collector.prepareMetrics();
    WafMetric metric = findMetric(collector.drain(), it -> "requests".equals(it.metricName));
    assertEquals(withCallPath(expectedTags), new HashSet<>(metric.tags));
  }

  @TableTest({
    "type           ",
    "CLIENT_ERROR   ",
    "BAD_STATUS     ",
    "BAD_RESPONSE   ",
    "REDACTION_ERROR"
  })
  void testAiGuardError(AIGuardError type) {
    // when
    collector.aiGuardError(type);

    // then
    collector.prepareMetrics();
    Collection<WafMetric> metrics = collector.drain();

    // a failed evaluation keeps the request count complete...
    WafMetric request = findMetric(metrics, it -> "requests".equals(it.metricName));
    assertEquals("ai_guard", request.namespace);
    assertEquals(1, request.value.longValue());
    assertEquals(withCallPath(singletonList("error:true")), new HashSet<>(request.tags));

    // ...and is classified under its own metric
    WafMetric error = findMetric(metrics, it -> "error".equals(it.metricName));
    assertEquals("count", error.type);
    assertEquals("ai_guard", error.namespace);
    assertEquals(1, error.value.longValue());
    assertEquals(withCallPath(singletonList("type:" + type.tagValue)), new HashSet<>(error.tags));
  }

  @Test
  void testAiGuardRedactionErrors() {
    // when
    collector.aiGuardRedactionErrors(3);

    // then
    collector.prepareMetrics();
    Collection<WafMetric> metrics = collector.drain();

    WafMetric error = findMetric(metrics, it -> "error".equals(it.metricName));
    assertEquals("ai_guard", error.namespace);
    // one count per replacement that could not be applied
    assertEquals(3, error.value.longValue());
    assertEquals(withCallPath(singletonList("type:redaction_error")), new HashSet<>(error.tags));

    // redaction never fails the evaluation it rode in on, so no request is counted as failed
    assertTrue(metrics.stream().noneMatch(it -> "requests".equals(it.metricName)));
  }

  @TableTest({
    "type    ",
    "MESSAGES",
    "CONTENT "
  })
  void testAiGuardTruncated(AIGuardTruncationType type) {
    // when
    collector.aiGuardTruncated(type);

    // then
    collector.prepareMetrics();
    List<WafMetric> truncatedMetrics = metricsNamed(collector.drain(), "truncated");

    WafMetric metric = truncatedMetrics.get(0);
    assertEquals("count", metric.type);
    assertEquals("truncated", metric.metricName);
    assertEquals("ai_guard", metric.namespace);
    assertEquals(1, metric.value.longValue());
    assertEquals(withCallPath(singletonList("type:" + type.tagValue)), new HashSet<>(metric.tags));
  }

  @TableTest({
    "scenario                 | ruleType          | blocked",
    "sql injection, block     | SQL_INJECTION     | true   ",
    "sql injection            | SQL_INJECTION     | false  ",
    "lfi, block               | LFI               | true   ",
    "lfi                      | LFI               | false  ",
    "ssrf request, block      | SSRF_REQUEST      | true   ",
    "ssrf request             | SSRF_REQUEST      | false  ",
    "ssrf response, block     | SSRF_RESPONSE     | true   ",
    "ssrf response            | SSRF_RESPONSE     | false  ",
    "shell injection, block   | SHELL_INJECTION   | true   ",
    "shell injection          | SHELL_INJECTION   | false  ",
    "command injection, block | COMMAND_INJECTION | true   ",
    "command injection        | COMMAND_INJECTION | false  "
  })
  void testRaspRuleMatchBlockTag(RuleType ruleType, boolean blocked) {
    // given
    collector.wafInit("waf_ver1", "rules.1", true);

    // when
    collector.raspRuleMatch(ruleType, blocked);

    // then
    collector.prepareMetrics();
    List<WafMetric> matchMetrics = metricsNamed(collector.drain(), "rasp.rule.match");

    assertEquals(1, matchMetrics.size());
    WafMetric metric = matchMetrics.get(0);
    assertEquals("count", metric.type);
    assertEquals(1, metric.value.longValue());
    assertEquals("appsec", metric.namespace);
    Set<String> expectedTags =
        ruleType.variant != null
            ? tagSet(
                "rule_type:" + ruleType.type,
                "rule_variant:" + ruleType.variant,
                "waf_version:waf_ver1",
                "event_rules_version:rules.1",
                "block:" + blocked)
            : tagSet("rule_type:" + ruleType.type, "waf_version:waf_ver1", "block:" + blocked);
    assertEquals(expectedTags, new HashSet<>(metric.tags));
  }

  @Test
  void testRaspRuleMatchDrainsBlockedAndNonBlockedAsSeparateMetrics() {
    // given
    collector.wafInit("waf_ver1", "rules.1", true);

    // when
    collector.raspRuleMatch(SQL_INJECTION, true);
    collector.raspRuleMatch(SQL_INJECTION, false);

    // then
    collector.prepareMetrics();
    List<WafMetric> matchMetrics = metricsNamed(collector.drain(), "rasp.rule.match");

    assertEquals(2, matchMetrics.size());
    assertEquals(
        1, findMetric(matchMetrics, it -> it.tags.contains("block:true")).value.longValue());
    assertEquals(
        1, findMetric(matchMetrics, it -> it.tags.contains("block:false")).value.longValue());
  }

  @Test
  void testApiSecurityMissingRouteMetric() {
    // when
    collector.apiSecurityMissingRoute("netty");

    // then
    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "api_security.missing_route");

    assertEquals(1, metrics.size());
    WafMetric metric = metrics.get(0);
    assertInstanceOf(ApiSecurityMissingRoute.class, metric);
    assertEquals("count", metric.type);
    assertEquals("appsec", metric.namespace);
    assertEquals(1, metric.value.longValue());
    assertEquals(tagSet("framework:netty"), new HashSet<>(metric.tags));
  }

  @Test
  void testApiSecurityMissingRouteMetricAggregatesMultipleCallsPerFramework() {
    // when: the same framework is hit twice and a different framework once, before a single drain
    collector.apiSecurityMissingRoute("netty");
    collector.apiSecurityMissingRoute("netty");
    collector.apiSecurityMissingRoute("tomcat");
    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "api_security.missing_route");

    // then: one aggregated metric per framework is emitted, not one per call
    assertEquals(2, metrics.size());
    assertEquals(
        2, findMetric(metrics, it -> it.tags.contains("framework:netty")).value.longValue());
    assertEquals(
        1, findMetric(metrics, it -> it.tags.contains("framework:tomcat")).value.longValue());

    // when: a further drain cycle happens with no new calls
    collector.prepareMetrics();
    List<WafMetric> secondDrainMetrics =
        metricsNamed(collector.drain(), "api_security.missing_route");

    // then: the per-framework counters were reset and nothing is re-emitted
    assertTrue(secondDrainMetrics.isEmpty());
  }

  /**
   * Runs last: the per-framework counters are never evicted, so once the cap is reached any new
   * framework (e.g. "netty" in the other missing route tests) would be folded into "unknown".
   */
  @Test
  @Order(Integer.MAX_VALUE)
  void testApiSecurityMissingRouteMetricCapsDistinctFrameworkCardinality() {
    // when: more distinct frameworks than the cardinality cap are reported
    for (int index = 1; index <= 100; index++) {
      collector.apiSecurityMissingRoute("framework-" + index);
    }
    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "api_security.missing_route");

    // then: the number of distinct framework tags emitted never exceeds the cap
    assertTrue(metrics.size() <= 64);
    assertTrue(
        findMetric(metrics, it -> it.tags.contains("framework:unknown")).value.longValue()
            >= 100 - 64);
  }

  @Test
  void testApiSecurityRequestSchemaMetric() {
    // when
    collector.apiSecurityRequestSchema("netty");

    // then
    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "api_security.request.schema");

    assertEquals(1, metrics.size());
    WafMetric metric = metrics.get(0);
    assertInstanceOf(ApiSecurityRequestSchema.class, metric);
    assertEquals("count", metric.type);
    assertEquals("appsec", metric.namespace);
    assertEquals(1, metric.value.longValue());
    assertEquals(tagSet("framework:netty"), new HashSet<>(metric.tags));
  }

  @Test
  void testApiSecurityRequestNoSchemaMetric() {
    // when
    collector.apiSecurityRequestNoSchema("netty");

    // then
    collector.prepareMetrics();
    List<WafMetric> metrics = metricsNamed(collector.drain(), "api_security.request.no_schema");

    assertEquals(1, metrics.size());
    WafMetric metric = metrics.get(0);
    assertInstanceOf(ApiSecurityRequestNoSchema.class, metric);
    assertEquals("count", metric.type);
    assertEquals("appsec", metric.namespace);
    assertEquals(1, metric.value.longValue());
    assertEquals(tagSet("framework:netty"), new HashSet<>(metric.tags));
  }

  @TableTest({
    "scenario                  | framework       | expectedFramework",
    "null framework            |                 | unknown          ",
    "empty framework           | ''              | unknown          ",
    "blank framework           | '   '           | unknown          ",
    "normal framework          | netty           | netty            ",
    "framework with whitespace | '  netty  '     | netty            ",
    "framework with mixed case | Netty           | netty            ",
    "framework with bad chars  | 'my framework!' | my_framework_    "
  })
  @MethodSource("testNormalizeFrameworkArguments")
  void testNormalizeFramework(String framework, String expectedFramework) {
    // expect
    assertEquals(expectedFramework, WafMetricCollector.normalizeFramework(framework));
  }

  static Stream<Arguments> testNormalizeFrameworkArguments() {
    return Stream.of(
        arguments(named("framework over 200 chars", repeat('a', 300)), repeat('a', 200)));
  }

  private void raspRuleEvalAndPrepare(int limit) {
    for (int index = 0; index <= limit * 2; index++) {
      collector.raspRuleEval(SQL_INJECTION);
      collector.prepareMetrics();
    }
  }

  private void submitMissingUserLogin(
      ExecutorService executors, CountDownLatch latch, LoginEvent event, int count) {
    for (int index = 0; index < count; index++) {
      executors.submit(() -> {
        latch.await();
        collector.missingUserLogin(SPRING_SECURITY, event);
        return null;
      });
    }
  }

  private static void assertAppSecCount(
      Class<? extends WafMetric> expectedType,
      WafMetric metric,
      String metricName,
      long value,
      Set<String> tags) {
    assertInstanceOf(expectedType, metric);
    assertEquals("count", metric.type);
    assertEquals(value, metric.value.longValue());
    assertEquals("appsec", metric.namespace);
    assertEquals(metricName, metric.metricName);
    assertEquals(tags, new HashSet<>(metric.tags));
  }

  private static List<WafMetric> metricsNamed(Collection<WafMetric> metrics, String metricName) {
    return metrics.stream()
        .filter(metric -> metricName.equals(metric.metricName))
        .collect(Collectors.toList());
  }

  private static WafMetric findMetric(
      Collection<WafMetric> metrics, Predicate<WafMetric> predicate) {
    WafMetric found = metrics.stream().filter(predicate).findFirst().orElse(null);
    assertNotNull(found);
    return found;
  }

  private static Map<String, String> tagMap(WafMetric metric) {
    Map<String, String> tags = new HashMap<>();
    for (String tag : metric.tags) {
      String[] parts = tag.split(":");
      tags.put(parts[0], parts[1]);
    }
    return tags;
  }

  private static Set<String> tagSet(String... tags) {
    return new HashSet<>(asList(tags));
  }

  private static Set<String> withCallPath(List<String> tags) {
    Set<String> result = new HashSet<>(tags);
    result.addAll(CALL_PATH);
    return result;
  }

  private static String repeat(char character, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, character);
    return new String(chars);
  }

  /** Generates all combinations of n boolean values. */
  private static Stream<Arguments> allBooleanCombinations(int n) {
    int total = 1 << n;
    List<Arguments> combinations = new ArrayList<>();
    for (int combination = 0; combination < total; combination++) {
      Object[] values = new Object[n];
      for (int bit = 0; bit < n; bit++) {
        values[bit] = ((combination >> bit) & 1) == 1;
      }
      combinations.add(arguments(values));
    }
    return combinations.stream();
  }
}
