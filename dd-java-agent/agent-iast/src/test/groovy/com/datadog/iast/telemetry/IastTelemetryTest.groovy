package com.datadog.iast.telemetry

import com.datadog.iast.IastRequestContext
import datadog.trace.api.Config
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastTelemetryCollector
import datadog.trace.api.iast.telemetry.Verbosity
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic

@CompileDynamic
class IastTelemetryTest extends DDSpecification {

  void 'test builder'() {
    given:
    final config = Mock(Config) {
      isTelemetryEnabled() >> { verbosity != null }
      getIastTelemetryVerbosity() >> verbosity
    }

    when:
    final telemetry = IastTelemetry.build(config)

    then:
    instance.isInstance(telemetry)

    where:
    verbosity             | instance
    null                  | NoOpTelemetry
    Verbosity.OFF         | NoOpTelemetry
    Verbosity.MANDATORY   | IastTelemetryImpl
    Verbosity.INFORMATION | IastTelemetryImpl
    Verbosity.DEBUG       | IastTelemetryImpl
  }

  void 'test no op telemetry'() {
    given:
    final telemetry = new NoOpTelemetry()
    final trace = Mock(TraceSegment)

    when:
    final context = telemetry.onRequestStarted()

    then:
    context instanceof IastRequestContext

    when:
    telemetry.onRequestEnded(context, trace)

    then:
    0 * _
  }

  void 'test telemetry impl'() {
    given:
    final telemetry = new IastTelemetryImpl(Verbosity.DEBUG)
    final trace = Mock(TraceSegment)
    final metric = IastMetric.TAINTED_FLAT_MODE

    when:
    final context = telemetry.onRequestStarted()

    then:
    context instanceof IastTelemetryCollector.HasTelemetryCollector

    when:
    final collector = (context as IastTelemetryCollector.HasTelemetryCollector).telemetryCollector
    collector.addMetric(metric, 1, null)
    telemetry.onRequestEnded(context, trace)

    then:
    1 * trace.setTagTop("dd.instrumentation_telemetry_data.iast.${metric.name}", 1L)
  }
}
