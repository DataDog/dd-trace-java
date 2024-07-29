package com.datadog.appsec.powerwaf

import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.api.internal.TraceSegment
import datadog.trace.test.util.DDSpecification
import io.sqreen.powerwaf.PowerwafMetrics

import java.util.concurrent.atomic.AtomicInteger

class PowerWAFStatsReporterSpecification extends DDSpecification {
  PowerWAFStatsReporter reporter = new PowerWAFStatsReporter()
  AppSecRequestContext ctx = Mock()

  void 'reporter reports waf timings and version'() {
    setup:
    PowerwafMetrics metrics = new PowerwafMetrics()
    metrics.totalRunTimeNs = 2_000
    metrics.totalDdwafRunTimeNs = 1_000
    TraceSegment segment = Mock()
    reporter.rulesVersion = '1.2.3'

    when:
    reporter.processTraceSegment(segment, ctx, [])

    then:
    1 * ctx.getWafMetrics() >> metrics
    1 * segment.setTagTop('_dd.appsec.waf.duration', 1)
    1 * segment.setTagTop('_dd.appsec.waf.duration_ext', 2)
    1 * segment.setTagTop('_dd.appsec.event_rules.version', '1.2.3')
  }

  void 'reporter reports rasp timings and version'() {
    setup:
    PowerwafMetrics metrics = new PowerwafMetrics()
    metrics.totalRunTimeNs = 2_000
    metrics.totalDdwafRunTimeNs = 1_000

    PowerwafMetrics raspMetrics = new PowerwafMetrics()
    raspMetrics.totalRunTimeNs = 4_000
    raspMetrics.totalDdwafRunTimeNs = 3_000
    TraceSegment segment = Mock()
    reporter.rulesVersion = '1.2.3'

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
