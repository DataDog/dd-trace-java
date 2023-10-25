package com.datadog.iast.telemetry.taint

import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.Ranges
import com.datadog.iast.taint.TaintedObject
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastMetricCollector
import datadog.trace.api.iast.telemetry.Verbosity
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic
import spock.lang.Shared

@CompileDynamic
class TaintedObjectsWithTelemetryTest extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  private IastMetricCollector mockCollector

  void setup() {
    mockCollector = Mock(IastMetricCollector)
    final iastCtx = Mock(IastRequestContext) {
      getMetricCollector() >> mockCollector
    }
    final ctx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> iastCtx
    }
    final span = Mock(AgentSpan) {
      getRequestContext() >> ctx
    }
    final api = Mock(AgentTracer.TracerAPI) {
      activeSpan() >> span
    }
    AgentTracer.forceRegister(api)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  void 'test request.tainted with #verbosity'() {
    given:
    final tainteds = [tainted(), tainted()]
    final taintedObjects = TaintedObjectsWithTelemetry.build(verbosity, Mock(TaintedObjects) {
      iterator() >> tainteds.iterator()
      count() >> tainteds.size()
    })

    when:
    taintedObjects.release()

    then:
    if (IastMetric.REQUEST_TAINTED.isEnabled(verbosity)) {
      1 * mockCollector.addMetric(IastMetric.REQUEST_TAINTED, _, tainteds.size())
    } else {
      0 * mockCollector.addMetric
    }

    where:
    verbosity << Verbosity.values().toList()
  }

  void 'test executed.tainted with #verbosity'() {
    given:
    final taintedObjects = TaintedObjectsWithTelemetry.build(verbosity, Mock(TaintedObjects))

    when:
    taintedObjects.taintInputString('test', new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value'))
    taintedObjects.taintInputObject(new Date(), new Source(SourceTypes.REQUEST_HEADER_VALUE, 'name', 'value'))
    taintedObjects.taint('test', new Range[0])

    then:
    if (IastMetric.EXECUTED_TAINTED.isEnabled(verbosity)) {
      3 * mockCollector.addMetric(IastMetric.EXECUTED_TAINTED, _, 1) // two calls with one element
    } else {
      0 * mockCollector.addMetric
    }

    where:
    verbosity << Verbosity.values().toList()
  }

  void 'test tainted.flat.mode with #verbosity'() {
    given:
    final taintedObjects = TaintedObjectsWithTelemetry.build(verbosity, Mock(TaintedObjects) {
      isFlat() >> true
    })

    when:
    taintedObjects.release()

    then:
    if (IastMetric.TAINTED_FLAT_MODE.isEnabled(verbosity) && taintedObjects.isFlat()) {
      1 * mockCollector.addMetric(IastMetric.TAINTED_FLAT_MODE, _, _)
    } else {
      0 * mockCollector.addMetric
    }

    where:
    verbosity << Verbosity.values().toList()
  }

  private TaintedObject tainted() {
    return new TaintedObject(UUID.randomUUID(), Ranges.EMPTY, null)
  }
}
