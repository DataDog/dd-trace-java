package com.datadog.iast.telemetry.taint

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObjects
import com.datadog.iast.telemetry.RequestContextWithTelemetry
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastTelemetryCollector
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

  private IastTelemetryCollector mockCollector

  void setup() {
    mockCollector = Mock(IastTelemetryCollector)
    final iastCtx = Mock(RequestContextWithTelemetry) {
      getTelemetryCollector() >> mockCollector
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
    final taintedObjects = TaintedObjectsWithTelemetry.build(verbosity, Mock(TaintedObjects) {
      getEstimatedSize() >> 2
    })

    when:
    taintedObjects.release()

    then:
    if (IastMetric.REQUEST_TAINTED.isEnabled(verbosity)) {
      1 * mockCollector.addMetric(IastMetric.REQUEST_TAINTED, taintedObjects.getEstimatedSize(), null)
    } else {
      0 * mockCollector.addMetric(_, _, _)
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
      3 * mockCollector.addMetric(IastMetric.EXECUTED_TAINTED, 1, null) // two calls with one element
    } else {
      0 * mockCollector.addMetric(_, _, _)
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
      0 * mockCollector.addMetric(_, _, _)
    }

    where:
    verbosity << Verbosity.values().toList()
  }
}
