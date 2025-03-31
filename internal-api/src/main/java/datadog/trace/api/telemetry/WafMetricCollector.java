package datadog.trace.api.telemetry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class WafMetricCollector implements MetricCollector<WafMetricCollector.WafMetric> {

  public static WafMetricCollector INSTANCE = new WafMetricCollector();
  private static final int ABSTRACT_POWERWAF_EXCEPTION_NUMBER =
      3; // only 3 error codes are possible for now in AbstractPowerwafException

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

  private static final AtomicRequestCounter wafRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafTriggeredRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafBlockedRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafTimeoutRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafErrorRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafRateLimitedRequestCounter =
      new AtomicRequestCounter();
  private static final AtomicRequestCounter wafBlockFailureRequestCounter =
      new AtomicRequestCounter();
  private static final AtomicLongArray wafInputTruncatedCounter =
      new AtomicLongArray(WafTruncatedType.values().length);
  private static final AtomicLongArray raspRuleEvalCounter =
      new AtomicLongArray(RuleType.getNumValues());
  private static final AtomicLongArray raspRuleSkippedCounter =
      new AtomicLongArray(RuleType.getNumValues());
  private static final AtomicLongArray raspRuleMatchCounter =
      new AtomicLongArray(RuleType.getNumValues());
  private static final AtomicLongArray raspTimeoutCounter =
      new AtomicLongArray(RuleType.getNumValues());
  private static final ConcurrentMap<Integer, AtomicLongArray> raspErrorCodeCounter =
      new ConcurrentSkipListMap<>();
  private static final ConcurrentMap<Integer, AtomicLongArray> wafErrorCodeCounter =
      new ConcurrentSkipListMap<>();

  static {
    for (int i = -1 * ABSTRACT_POWERWAF_EXCEPTION_NUMBER; i < 0; i++) {
      raspErrorCodeCounter.put(i, new AtomicLongArray(RuleType.getNumValues()));
      wafErrorCodeCounter.put(i, new AtomicLongArray(RuleType.getNumValues()));
    }
  }

  private static final AtomicLongArray missingUserLoginQueue =
      new AtomicLongArray(LoginFramework.getNumValues() * LoginEvent.getNumValues());
  private static final AtomicLongArray missingUserIdQueue =
      new AtomicLongArray(LoginFramework.getNumValues());
  private static final AtomicLongArray appSecSdkEventQueue =
      new AtomicLongArray(LoginEvent.getNumValues() * LoginVersion.getNumValues());

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

  public void wafRequest() {
    wafRequestCounter.increment();
  }

  public void wafRequestTriggered() {
    wafTriggeredRequestCounter.increment();
  }

  public void wafRequestBlocked() {
    wafBlockedRequestCounter.increment();
  }

  public void wafRequestTimeout() {
    wafTimeoutRequestCounter.increment();
  }

  public void wafRequestError() {
    wafErrorRequestCounter.increment();
  }

  public void wafRequestRateLimited() {
    wafRateLimitedRequestCounter.increment();
  }

  public void wafRequestBlockFailure() {
    wafBlockFailureRequestCounter.increment();
  }

  public void wafInputTruncated(final WafTruncatedType wafTruncatedType, long increment) {
    wafInputTruncatedCounter.addAndGet(wafTruncatedType.ordinal(), increment);
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

  public void raspErrorCode(final RuleType ruleType, final int ddwafRunErrorCode) {
    raspErrorCodeCounter.get(ddwafRunErrorCode).incrementAndGet(ruleType.ordinal());
  }

  public void wafErrorCode(final RuleType ruleType, final int ddwafRunErrorCode) {
    wafErrorCodeCounter.get(ddwafRunErrorCode).incrementAndGet(ruleType.ordinal());
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
    final boolean isRateLimited = wafRateLimitedRequestCounter.getAndReset() > 0;
    final boolean isBlockFailure = wafBlockFailureRequestCounter.getAndReset() > 0;
    boolean isWafInputTruncated = false;
    for (WafTruncatedType wafTruncatedType : WafTruncatedType.values()) {
      isWafInputTruncated = wafInputTruncatedCounter.getAndSet(wafTruncatedType.ordinal(), 0) > 0;
      if (isWafInputTruncated) {
        break;
      }
    }

    // Requests
    if (wafRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(
          new WafRequestsRawMetric(
              wafRequestCounter.getAndReset(),
              WafMetricCollector.wafVersion,
              WafMetricCollector.rulesVersion,
              false,
              false,
              false,
              false,
              isBlockFailure,
              isRateLimited,
              isWafInputTruncated))) {
        return;
      }
    }

    // Triggered requests
    if (wafTriggeredRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(
          new WafRequestsRawMetric(
              wafTriggeredRequestCounter.getAndReset(),
              WafMetricCollector.wafVersion,
              WafMetricCollector.rulesVersion,
              true,
              false,
              false,
              false,
              isBlockFailure,
              isRateLimited,
              isWafInputTruncated))) {
        return;
      }
    }

    // Blocked requests
    if (wafBlockedRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(
          new WafRequestsRawMetric(
              wafBlockedRequestCounter.getAndReset(),
              WafMetricCollector.wafVersion,
              WafMetricCollector.rulesVersion,
              true,
              true,
              false,
              false,
              isBlockFailure,
              isRateLimited,
              isWafInputTruncated))) {
        return;
      }
    }

    // Timeout requests
    if (wafTimeoutRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(
          new WafRequestsRawMetric(
              wafTimeoutRequestCounter.getAndReset(),
              WafMetricCollector.wafVersion,
              WafMetricCollector.rulesVersion,
              false,
              false,
              false,
              true,
              isBlockFailure,
              isRateLimited,
              isWafInputTruncated))) {
        return;
      }
    }

    // WAF error requests
    if (wafErrorRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(
          new WafRequestsRawMetric(
              wafErrorRequestCounter.getAndReset(),
              WafMetricCollector.wafVersion,
              WafMetricCollector.rulesVersion,
              false,
              false,
              true,
              false,
              isBlockFailure,
              isRateLimited,
              isWafInputTruncated))) {
        return;
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
    for (int i = -1 * ABSTRACT_POWERWAF_EXCEPTION_NUMBER; i < 0; i++) {
      for (RuleType ruleType : RuleType.values()) {
        long counter = raspErrorCodeCounter.get(i).getAndSet(ruleType.ordinal(), 0);
        if (counter > 0) {
          if (!rawMetricsQueue.offer(
              new RaspError(counter, ruleType, WafMetricCollector.wafVersion, i))) {
            return;
          }
          if (!rawMetricsQueue.offer(
              new WafError(counter, ruleType, WafMetricCollector.wafVersion, i))) {
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

    // RASP rule skipped per rule type for after-request reason
    for (RuleType ruleType : RuleType.values()) {
      long counter = raspRuleSkippedCounter.getAndSet(ruleType.ordinal(), 0);
      if (counter > 0) {
        if (!rawMetricsQueue.offer(new AfterRequestRaspRuleSkipped(counter, ruleType))) {
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
    public WafError(
        final long counter,
        final RuleType ruleType,
        final String wafVersion,
        final Integer ddwafRunError) {
      super(
          "waf.error",
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

  public static class AtomicRequestCounter {

    private final AtomicLong atomicLong = new AtomicLong();
    private volatile long timestamp;

    public final long get() {
      return atomicLong.get();
    }

    public final long getAndReset() {
      timestamp = 0;
      return atomicLong.getAndSet(0);
    }

    public final void increment() {
      if (timestamp == 0) {
        timestamp = System.currentTimeMillis();
      }
      atomicLong.incrementAndGet();
    }
  }
}
