package datadog.trace.api.telemetry;

import datadog.trace.api.aiguard.AIGuard;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

public class WafMetricCollector implements MetricCollector<WafMetricCollector.WafMetric> {

  private static final int MASK_STRING_TOO_LONG = 1; // 0b001
  private static final int MASK_LIST_MAP_TOO_LARGE = 1 << 1; // 0b010
  private static final int MASK_OBJECT_TOO_DEEP = 1 << 2; // 0b100

  public static WafMetricCollector INSTANCE = new WafMetricCollector();

  public static WafMetricCollector get() {
    return WafMetricCollector.INSTANCE;
  }

  private WafMetricCollector() {
    // Prevent external instantiation
  }

  private static final String NAMESPACE = "appsec";

  private static final BlockingQueue<WafMetric> rawMetricsQueue =
      new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);

  private static final AtomicInteger wafInitCounter = new AtomicInteger();
  private static final AtomicInteger wafUpdatesCounter = new AtomicInteger();

  private static final int WAF_REQUEST_COMBINATIONS = 128; // 2^7
  private final AtomicLongArray wafRequestCounter = new AtomicLongArray(WAF_REQUEST_COMBINATIONS);

  private static final AtomicLongArray wafInputTruncatedCounter =
      new AtomicLongArray(1 << 3); // 3 flags → 2^3 = 8 possible bit combinations

  private static final AtomicLongArray raspRuleEvalCounter =
      new AtomicLongArray(RuleType.getNumValues());
  private static final AtomicLongArray raspRuleSkippedCounter =
      new AtomicLongArray(RuleType.getNumValues());
  private static final AtomicLongArray raspRuleMatchCounter =
      new AtomicLongArray(RuleType.getNumValues());
  private static final AtomicLongArray raspTimeoutCounter =
      new AtomicLongArray(RuleType.getNumValues());
  private static final AtomicLongArray raspErrorCodeCounter =
      new AtomicLongArray(WafErrorCode.values().length * RuleType.getNumValues());
  private static final AtomicLongArray wafErrorCodeCounter =
      new AtomicLongArray(WafErrorCode.values().length);
  private static final AtomicLongArray missingUserLoginQueue =
      new AtomicLongArray(LoginFramework.getNumValues() * LoginEvent.getNumValues());
  private static final AtomicLongArray missingUserIdQueue =
      new AtomicLongArray(LoginFramework.getNumValues());
  private static final AtomicLongArray appSecSdkEventQueue =
      new AtomicLongArray(LoginEvent.getNumValues() * LoginVersion.getNumValues());
  private static final AtomicInteger wafConfigErrorCounter = new AtomicInteger();
  private static final AtomicLongArray aiGuardRequests =
      new AtomicLongArray(AIGuard.Action.values().length * 2); // 3 actions * block
  private static final AtomicInteger aiGuardErrors = new AtomicInteger();
  private static final AtomicLongArray aiGuardTruncated =
      new AtomicLongArray(AIGuardTruncationType.values().length);

  /** WAF version that will be initialized with wafInit and reused for all metrics. */
  private static String wafVersion = "";

  /**
   * Rules version that will be updated on each wafInit and wafUpdates. This is not entirely
   * accurate, since wafRequest metrics might be collected for a period where a rules update happens
   * and some requests will be incorrectly reported with the old or new rules version.
   */
  private static String rulesVersion = "";

  public void wafInit(final String wafVersion, final String rulesVersion, final boolean success) {
    WafMetricCollector.wafVersion = wafVersion;
    WafMetricCollector.rulesVersion = rulesVersion;
    rawMetricsQueue.offer(
        new WafInitRawMetric(wafInitCounter.incrementAndGet(), wafVersion, rulesVersion, success));
  }

  public void wafUpdates(final String rulesVersion, final boolean success) {
    rawMetricsQueue.offer(
        new WafUpdatesRawMetric(
            wafUpdatesCounter.incrementAndGet(), wafVersion, rulesVersion, success));

    // Flush request metrics to get the new version.
    if (rulesVersion != null
        && WafMetricCollector.rulesVersion != null
        && !rulesVersion.equals(WafMetricCollector.rulesVersion)) {
      WafMetricCollector.get().prepareMetrics();
    }
    WafMetricCollector.rulesVersion = rulesVersion;
  }

  public void wafRequest(
      final boolean ruleTriggered,
      final boolean requestBlocked,
      final boolean wafError,
      final boolean wafTimeout,
      final boolean blockFailure,
      final boolean rateLimited,
      final boolean inputTruncated) {
    int index =
        computeWafRequestIndex(
            ruleTriggered,
            requestBlocked,
            wafError,
            wafTimeout,
            blockFailure,
            rateLimited,
            inputTruncated);
    wafRequestCounter.incrementAndGet(index);
  }

  public void wafInputTruncated(
      final boolean stringTooLong, final boolean listMapTooLarge, final boolean objectTooDeep) {
    int index = computeWafInputTruncatedIndex(stringTooLong, listMapTooLarge, objectTooDeep);
    wafInputTruncatedCounter.incrementAndGet(index);
  }

  static int computeWafRequestIndex(
      boolean ruleTriggered,
      boolean requestBlocked,
      boolean wafError,
      boolean wafTimeout,
      boolean blockFailure,
      boolean rateLimited,
      boolean inputTruncated) {
    int index = 0;
    if (ruleTriggered) index |= 1;
    if (requestBlocked) index |= 1 << 1;
    if (wafError) index |= 1 << 2;
    if (wafTimeout) index |= 1 << 3;
    if (blockFailure) index |= 1 << 4;
    if (rateLimited) index |= 1 << 5;
    if (inputTruncated) index |= 1 << 6;
    return index;
  }

  static int computeWafInputTruncatedIndex(
      boolean stringTooLong, boolean listMapTooLarge, boolean objectTooDeep) {
    int index = 0;
    if (stringTooLong) index |= MASK_STRING_TOO_LONG;
    if (listMapTooLarge) index |= MASK_LIST_MAP_TOO_LARGE;
    if (objectTooDeep) index |= MASK_OBJECT_TOO_DEEP;
    return index;
  }

  public void raspRuleEval(final RuleType ruleType) {
    raspRuleEvalCounter.incrementAndGet(ruleType.ordinal());
  }

  public void raspRuleSkipped(final RuleType ruleType) {
    raspRuleSkippedCounter.incrementAndGet(ruleType.ordinal());
  }

  public void raspRuleMatch(final RuleType ruleType) {
    raspRuleMatchCounter.incrementAndGet(ruleType.ordinal());
  }

  public void raspTimeout(final RuleType ruleType) {
    raspTimeoutCounter.incrementAndGet(ruleType.ordinal());
  }

  public void raspErrorCode(RuleType ruleType, final int errorCode) {
    WafErrorCode wafErrorCode = WafErrorCode.fromCode(errorCode);
    // Unsupported waf error code
    if (wafErrorCode == null) {
      return;
    }
    int index = wafErrorCode.ordinal() * RuleType.getNumValues() + ruleType.ordinal();
    raspErrorCodeCounter.incrementAndGet(index);
  }

  public void wafErrorCode(final int errorCode) {
    WafErrorCode wafErrorCode = WafErrorCode.fromCode(errorCode);
    // Unsupported waf error code
    if (wafErrorCode == null) {
      return;
    }
    wafErrorCodeCounter.incrementAndGet(wafErrorCode.ordinal());
  }

  public void missingUserLogin(final LoginFramework framework, final LoginEvent eventType) {
    missingUserLoginQueue.incrementAndGet(
        framework.ordinal() * LoginEvent.getNumValues() + eventType.ordinal());
  }

  public void missingUserId(final LoginFramework framework) {
    missingUserIdQueue.incrementAndGet(framework.ordinal());
  }

  public void appSecSdkEvent(final LoginEvent event, final LoginVersion version) {
    final int index = event.ordinal() * LoginVersion.getNumValues() + version.ordinal();
    appSecSdkEventQueue.incrementAndGet(index);
  }

  public void aiGuardRequest(final AIGuard.Action action, final boolean block) {
    aiGuardRequests.incrementAndGet(action.ordinal() * 2 + (block ? 1 : 0));
  }

  public void aiGuardError() {
    aiGuardErrors.incrementAndGet();
  }

  public void aiGuardTruncated(final AIGuardTruncationType type) {
    aiGuardTruncated.incrementAndGet(type.ordinal());
  }

  @Override
  public Collection<WafMetric> drain() {
    if (!rawMetricsQueue.isEmpty()) {
      List<WafMetric> list = new LinkedList<>();
      int drained = rawMetricsQueue.drainTo(list);
      if (drained > 0) {
        return list;
      }
    }
    return Collections.emptyList();
  }

  @Override
  public void prepareMetrics() {

    // Requests
    for (int i = 0; i < WAF_REQUEST_COMBINATIONS; i++) {
      long counter = wafRequestCounter.getAndSet(i, 0);
      if (counter > 0) {
        boolean ruleTriggered = (i & 1) != 0;
        boolean requestBlocked = (i & (1 << 1)) != 0;
        boolean wafError = (i & (1 << 2)) != 0;
        boolean wafTimeout = (i & (1 << 3)) != 0;
        boolean blockFailure = (i & (1 << 4)) != 0;
        boolean rateLimited = (i & (1 << 5)) != 0;
        boolean inputTruncated = (i & (1 << 6)) != 0;

        if (!rawMetricsQueue.offer(
            new WafRequestsRawMetric(
                counter,
                WafMetricCollector.wafVersion,
                WafMetricCollector.rulesVersion,
                ruleTriggered,
                requestBlocked,
                wafError,
                wafTimeout,
                blockFailure,
                rateLimited,
                inputTruncated))) {
          return;
        }
      }
    }

    // WAF input truncated
    for (int i = 0; i < (1 << 3); i++) {
      long counter = wafInputTruncatedCounter.getAndSet(i, 0);
      if (counter > 0) {
        if (!rawMetricsQueue.offer(new WafInputTruncated(counter, i))) {
          return;
        }
      }
    }

    // RASP rule eval per rule type
    for (RuleType ruleType : RuleType.values()) {
      long counter = raspRuleEvalCounter.getAndSet(ruleType.ordinal(), 0);
      if (counter > 0) {
        if (!rawMetricsQueue.offer(
            new RaspRuleEval(counter, ruleType, WafMetricCollector.wafVersion))) {
          return;
        }
      }
    }

    // RASP rule match per rule type
    for (RuleType ruleType : RuleType.values()) {
      long counter = raspRuleMatchCounter.getAndSet(ruleType.ordinal(), 0);
      if (counter > 0) {
        if (!rawMetricsQueue.offer(
            new RaspRuleMatch(counter, ruleType, WafMetricCollector.wafVersion))) {
          return;
        }
      }
    }

    // RASP timeout per rule type
    for (RuleType ruleType : RuleType.values()) {
      long counter = raspTimeoutCounter.getAndSet(ruleType.ordinal(), 0);
      if (counter > 0) {
        if (!rawMetricsQueue.offer(
            new RaspTimeout(counter, ruleType, WafMetricCollector.wafVersion))) {
          return;
        }
      }
    }

    // RASP rule type for each possible error code
    for (WafErrorCode errorCode : WafErrorCode.values()) {
      for (RuleType ruleType : RuleType.values()) {
        int index = errorCode.ordinal() * RuleType.getNumValues() + ruleType.ordinal();
        long count = raspErrorCodeCounter.getAndSet(index, 0);
        if (count > 0) {
          if (!rawMetricsQueue.offer(
              new RaspError(count, ruleType, WafMetricCollector.wafVersion, errorCode.getCode()))) {
            return;
          }
        }
      }
    }

    // Missing user login
    for (LoginFramework framework : LoginFramework.values()) {
      for (LoginEvent event : LoginEvent.values()) {
        final int ordinal = framework.ordinal() * LoginEvent.getNumValues() + event.ordinal();
        long counter = missingUserLoginQueue.getAndSet(ordinal, 0);
        if (counter > 0) {
          if (!rawMetricsQueue.offer(
              new MissingUserLoginMetric(counter, framework.getTag(), event.getTag()))) {
            return;
          }
        }
      }
    }

    // Missing user id
    for (LoginFramework framework : LoginFramework.values()) {
      long counter = missingUserIdQueue.getAndSet(framework.ordinal(), 0);
      if (counter > 0) {
        if (!rawMetricsQueue.offer(new MissingUserIdMetric(counter, framework.getTag()))) {
          return;
        }
      }
    }

    // ATO login events
    for (LoginEvent event : LoginEvent.values()) {
      for (LoginVersion version : LoginVersion.values()) {
        final int ordinal = event.ordinal() * LoginVersion.getNumValues() + version.ordinal();
        long counter = appSecSdkEventQueue.getAndSet(ordinal, 0);
        if (counter > 0) {
          if (!rawMetricsQueue.offer(
              new AppSecSdkEvent(counter, event.getTag(), version.getTag()))) {
            return;
          }
        }
      }
    }

    // WAF rule type for each possible error code
    for (WafErrorCode errorCode : WafErrorCode.values()) {
      long count = wafErrorCodeCounter.getAndSet(errorCode.ordinal(), 0);
      if (count > 0) {
        if (!rawMetricsQueue.offer(
            new WafError(count, WafMetricCollector.wafVersion, errorCode.getCode()))) {
          return;
        }
      }
    }

    // RASP rule skipped per rule type for after-request reason
    for (RuleType ruleType : RuleType.values()) {
      long counter = raspRuleSkippedCounter.getAndSet(ruleType.ordinal(), 0);
      if (counter > 0) {
        if (!rawMetricsQueue.offer(new AfterRequestRaspRuleSkipped(counter, ruleType))) {
          return;
        }
      }
    }

    // WAF config errors
    int configErrors = wafConfigErrorCounter.getAndSet(0);
    if (configErrors > 0) {
      if (!rawMetricsQueue.offer(
          new WafConfigError(
              configErrors, WafMetricCollector.wafVersion, WafMetricCollector.rulesVersion))) {
        return;
      }
    }

    // AI Guard successful requests
    for (final AIGuard.Action action : AIGuard.Action.values()) {
      final long blocked = aiGuardRequests.getAndSet(action.ordinal() * 2 + 1, 0);
      if (blocked > 0) {
        if (!rawMetricsQueue.offer(AIGuardRequests.success(blocked, action, true))) {
          break;
        }
      }
      final long nonBlocked = aiGuardRequests.getAndSet(action.ordinal() * 2, 0);
      if (nonBlocked > 0) {
        if (!rawMetricsQueue.offer(AIGuardRequests.success(nonBlocked, action, false))) {
          break;
        }
      }
    }

    // AI Guard failed requests
    final int aiGuardErrorRequests = aiGuardErrors.getAndSet(0);
    if (aiGuardErrorRequests > 0) {
      if (!rawMetricsQueue.offer(AIGuardRequests.error(aiGuardErrorRequests))) {
        return;
      }
    }

    // AI Guard truncated messages
    for (final AIGuardTruncationType type : AIGuardTruncationType.values()) {
      final long count = aiGuardTruncated.getAndSet(type.ordinal(), 0);
      if (count > 0) {
        if (!rawMetricsQueue.offer(new AIGuardTruncated(count, type))) {
          return;
        }
      }
    }
  }

  public abstract static class WafMetric extends MetricCollector.Metric {

    public WafMetric(String metricName, long counter, String... tags) {
      super(NAMESPACE, true, metricName, "count", counter, tags);
    }
  }

  public static class WafInitRawMetric extends WafMetric {
    public WafInitRawMetric(
        final long counter,
        final String wafVersion,
        final String rulesVersion,
        final boolean success) {
      super(
          "waf.init",
          counter,
          "waf_version:" + wafVersion,
          "event_rules_version:" + rulesVersion,
          "success:" + success);
    }
  }

  public static class WafUpdatesRawMetric extends WafMetric {
    public WafUpdatesRawMetric(
        final long counter,
        final String wafVersion,
        final String rulesVersion,
        final boolean success) {
      super(
          "waf.updates",
          counter,
          "waf_version:" + wafVersion,
          "event_rules_version:" + rulesVersion,
          "success:" + success);
    }
  }

  public static class MissingUserLoginMetric extends WafMetric {

    public MissingUserLoginMetric(long counter, String framework, String type) {
      super(
          "instrum.user_auth.missing_user_login",
          counter,
          "framework:" + framework,
          "event_type:" + type);
    }
  }

  public static class MissingUserIdMetric extends WafMetric {

    public MissingUserIdMetric(long counter, String framework) {
      super(
          "instrum.user_auth.missing_user_id",
          counter,
          "framework:" + framework,
          "event_type:authenticated_request");
    }
  }

  public static class AppSecSdkEvent extends WafMetric {

    public AppSecSdkEvent(long counter, String event, final String version) {
      super("sdk.event", counter, "event_type:" + event, "sdk_version:" + version);
    }
  }

  public static class WafRequestsRawMetric extends WafMetric {
    public WafRequestsRawMetric(
        final long counter,
        final String wafVersion,
        final String rulesVersion,
        final boolean triggered,
        final boolean blocked,
        final boolean wafError,
        final boolean wafTimeout,
        final boolean blockFailure,
        final boolean rateLimited,
        final boolean inputTruncated) {
      super(
          "waf.requests",
          counter,
          "waf_version:" + wafVersion,
          "event_rules_version:" + rulesVersion,
          "rule_triggered:" + triggered,
          "request_blocked:" + blocked,
          "waf_error:" + wafError,
          "waf_timeout:" + wafTimeout,
          "block_failure:" + blockFailure,
          "rate_limited:" + rateLimited,
          "input_truncated:" + inputTruncated);
    }
  }

  public void addWafConfigError(int nbErrors) {
    wafConfigErrorCounter.addAndGet(nbErrors);
  }

  public static class WafConfigError extends WafMetric {
    public WafConfigError(final long counter, final String wafVersion, final String rulesVersion) {
      super(
          "waf.config_errors",
          counter,
          "waf_version:" + wafVersion,
          "event_rules_version:" + rulesVersion);
    }
  }

  public static class RaspRuleEval extends WafMetric {
    public RaspRuleEval(final long counter, final RuleType ruleType, final String wafVersion) {
      super(
          "rasp.rule.eval",
          counter,
          ruleType.variant != null
              ? new String[] {
                "rule_type:" + ruleType.type,
                "rule_variant:" + ruleType.variant,
                "waf_version:" + wafVersion,
                "event_rules_version:" + rulesVersion
              }
              : new String[] {"rule_type:" + ruleType.type, "waf_version:" + wafVersion});
    }
  }

  // Although rasp.rule.skipped reason could be before-request, there is no real case scenario
  public static class AfterRequestRaspRuleSkipped extends WafMetric {
    public AfterRequestRaspRuleSkipped(final long counter, final RuleType ruleType) {
      super(
          "rasp.rule.skipped",
          counter,
          ruleType.variant != null
              ? new String[] {
                "rule_type:" + ruleType.type,
                "rule_variant:" + ruleType.variant,
                "reason:" + "after-request"
              }
              : new String[] {"rule_type:" + ruleType.type, "reason:" + "after-request"});
    }
  }

  public static class RaspRuleMatch extends WafMetric {
    public RaspRuleMatch(final long counter, final RuleType ruleType, final String wafVersion) {
      super(
          "rasp.rule.match",
          counter,
          ruleType.variant != null
              ? new String[] {
                "rule_type:" + ruleType.type,
                "rule_variant:" + ruleType.variant,
                "waf_version:" + wafVersion,
                "event_rules_version:" + rulesVersion
              }
              : new String[] {"rule_type:" + ruleType.type, "waf_version:" + wafVersion});
    }
  }

  public static class RaspTimeout extends WafMetric {
    public RaspTimeout(final long counter, final RuleType ruleType, final String wafVersion) {
      super(
          "rasp.timeout",
          counter,
          ruleType.variant != null
              ? new String[] {
                "rule_type:" + ruleType.type,
                "rule_variant:" + ruleType.variant,
                "waf_version:" + wafVersion,
                "event_rules_version:" + rulesVersion
              }
              : new String[] {"rule_type:" + ruleType.type, "waf_version:" + wafVersion});
    }
  }

  public static class RaspError extends WafMetric {
    public RaspError(
        final long counter,
        final RuleType ruleType,
        final String wafVersion,
        final Integer ddwafRunError) {
      super(
          "rasp.error",
          counter,
          ruleType.variant != null
              ? new String[] {
                "rule_type:" + ruleType.type,
                "rule_variant:" + ruleType.variant,
                "waf_version:" + wafVersion,
                "event_rules_version:" + rulesVersion,
                "waf_error:" + ddwafRunError
              }
              : new String[] {
                "rule_type:" + ruleType.type,
                "waf_version:" + wafVersion,
                "waf_error:" + ddwafRunError
              });
    }
  }

  public static class WafError extends WafMetric {
    public WafError(final long counter, final String wafVersion, final Integer ddwafRunError) {
      super(
          "waf.error",
          counter,
          "waf_version:" + wafVersion,
          "event_rules_version:" + rulesVersion,
          "waf_error:" + ddwafRunError);
    }
  }

  public static class WafInputTruncated extends WafMetric {
    public WafInputTruncated(final long counter, final int bitfield) {
      super("waf.input_truncated", counter, "truncation_reason:" + bitfield);
    }
  }

  public static class AIGuardRequests extends WafMetric {
    private AIGuardRequests(final long count, final String... tags) {
      super("ai_guard.requests", count, tags);
    }

    public static AIGuardRequests success(
        final long count, final AIGuard.Action action, final boolean block) {
      return new AIGuardRequests(count, "action:" + action, "block:" + block, "error:false");
    }

    public static AIGuardRequests error(final long count) {
      return new AIGuardRequests(count, "error:true");
    }
  }

  public static class AIGuardTruncated extends WafMetric {
    public AIGuardTruncated(final long count, final AIGuardTruncationType type) {
      super("ai_guard.truncated", count, "type:" + type.tagValue);
    }
  }

  public enum AIGuardTruncationType {
    MESSAGES("messages"),
    CONTENT("content");
    public final String tagValue;

    AIGuardTruncationType(final String tagValue) {
      this.tagValue = tagValue;
    }
  }

  /**
   * Mirror of the {@code WafErrorCode} enum defined in the {@code libddwaf-java} module.
   *
   * <p>This enum is duplicated here to avoid adding a dependency on the native bindings module
   * (`libddwaf-java`) within the {@code internal-api} module.
   *
   * <p>IMPORTANT: If the {@code WafErrorCode} definition in {@code libddwaf-java} is updated, this
   * enum must be kept in sync manually to ensure correct behavior and compatibility.
   *
   * <p>Each enum value represents a specific WAF error condition, typically returned when running a
   * WAF rule evaluation.
   */
  public enum WafErrorCode {
    INVALID_ARGUMENT(-1),
    INVALID_OBJECT(-2),
    INTERNAL_ERROR(-3),
    BINDING_ERROR(
        -127); // This is a special error code that is not returned by the WAF, is used to signal a
    // binding error

    private final int code;

    private static final Map<Integer, WafErrorCode> CODE_MAP;

    static {
      Map<Integer, WafErrorCode> map = new HashMap<>();
      for (WafErrorCode errorCode : values()) {
        map.put(errorCode.code, errorCode);
      }
      CODE_MAP = Collections.unmodifiableMap(map);
    }

    WafErrorCode(int code) {
      this.code = code;
    }

    public int getCode() {
      return code;
    }

    public static WafErrorCode fromCode(int code) {
      return CODE_MAP.get(code);
    }
  }
}
