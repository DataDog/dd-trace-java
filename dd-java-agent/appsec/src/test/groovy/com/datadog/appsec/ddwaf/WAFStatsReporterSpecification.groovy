package com.datadog.appsec.ddwaf

import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.api.internal.TraceSegment
import datadog.trace.test.util.DDSpecification
import com.datadog.ddwaf.WafMetrics

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WAFStatsReporterSpecification extends DDSpecification {
  WAFStatsReporter reporter = new WAFStatsReporter()
  AppSecRequestContext ctx = Mock()

  void 'reporter reports waf timings and version'() {
    setup:
    WafMetrics metrics = new WafMetrics()
    metrics.totalRunTimeNs = new AtomicLong(2_000)
    metrics.totalDdwafRunTimeNs = new AtomicLong(1_000)
    TraceSegment segment = Mock()
    reporter.rulesVersion = '1.2.3'
    def wafTimeouts = 1

    and:
    ctx.getWafTimeouts() >> wafTimeouts

    when:
    reporter.processTraceSegment(segment, ctx, [])

    then:
    1 * ctx.getWafMetrics() >> metrics
    1 * segment.setTagTop('_dd.appsec.waf.duration', 1)
    1 * segment.setTagTop('_dd.appsec.waf.duration_ext', 2)
    1 * segment.setTagTop('_dd.appsec.event_rules.version', '1.2.3')
    1 * segment.setTagTop('_dd.appsec.waf.timeouts', wafTimeouts)
  }

  void 'reporter reports rasp timings and version'() {
    setup:
    WafMetrics metrics = new WafMetrics()
    metrics.totalRunTimeNs = new AtomicLong(2_000)
    metrics.totalDdwafRunTimeNs = new AtomicLong(1_000)

    WafMetrics raspMetrics = new WafMetrics()
    raspMetrics.totalRunTimeNs = new AtomicLong(4_000)
    raspMetrics.totalDdwafRunTimeNs = new AtomicLong(3_000)
    TraceSegment segment = Mock()
    reporter.rulesVersion = '1.2.3'
    def raspTimeouts = 1

    and:
    ctx.getRaspTimeouts() >> raspTimeouts

    when:
    reporter.processTraceSegment(segment, ctx, [])

    then:
    1 * ctx.getWafMetrics() >> null
    1 * ctx.getRaspMetrics() >> raspMetrics
    1 * ctx.getRaspMetricsCounter() >> new AtomicInteger(5)
    1 * segment.setTagTop('_dd.appsec.rasp.duration', 3)
    1 * segment.setTagTop('_dd.appsec.rasp.duration_ext', 4)
    1 * segment.setTagTop('_dd.appsec.rasp.rule.eval', 5)
    1 * segment.setTagTop('_dd.appsec.event_rules.version', '1.2.3')
    1 * segment.setTagTop('_dd.appsec.rasp.timeout', raspTimeouts)
  }

  void 'reports nothing if metrics are null'() {
    setup:
    TraceSegment segment = Mock()

    when:
    reporter.processTraceSegment(segment, ctx, [])

    then:
    1 * ctx.getWafMetrics() >> null
    0 * segment._(*_)
  }
}
