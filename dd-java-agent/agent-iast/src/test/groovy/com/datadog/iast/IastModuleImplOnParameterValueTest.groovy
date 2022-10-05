package com.datadog.iast

import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class IastModuleImplOnParameterValueTest extends IastModuleImplTestBase {

  void 'onParameterValue null or empty'() {
    when:
    module.onParameterValue(paramName, paramValue)

    then:
    0 * _

    where:
    paramName | paramValue
    null      | null
    null      | ""
    ""        | null
    ""        | ""
    "param"   | null
    "param"   | ""
  }

  void 'onParameterValue without span'() {
    when:
    module.onParameterValue(paramName, paramValue)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    paramName | paramValue
    null      | "value"
    ""        | "value"
    "param"   | "value"
  }

  void 'onParameterValue'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    when:
    module.onParameterValue(paramName, paramValue)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    ctx.getTaintedObjects().get(paramName) == null
    def to = ctx.getTaintedObjects().get(paramValue)
    to != null
    to.get() == paramValue
    to.ranges.size() == 1
    to.ranges[0].start == 0
    to.ranges[0].length == paramValue.length()
    to.ranges[0].source == new Source(SourceType.REQUEST_PARAMETER_VALUE, paramName, paramValue)

    where:
    paramName | paramValue
    null      | "value"
    ""        | "value"
    "param"   | "value"
  }
}
