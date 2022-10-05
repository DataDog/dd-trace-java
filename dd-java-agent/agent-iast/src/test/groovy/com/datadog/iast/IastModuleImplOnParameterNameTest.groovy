package com.datadog.iast

import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class IastModuleImplOnParameterNameTest extends IastModuleImplTestBase {

  void 'onParameterName null or empty'() {
    when:
    module.onParameterName(paramName)

    then:
    0 * _

    where:
    paramName | _
    null      | _
    ""        | _
  }

  void 'onParameterName without span'() {
    when:
    module.onParameterName(paramName)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    paramName | _
    "param"   | _
  }

  void 'onParameterName'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    when:
    module.onParameterName(paramName)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(paramName)
    to != null
    to.get() == paramName
    to.ranges.size() == 1
    to.ranges[0].start == 0
    to.ranges[0].length == paramName.length()
    to.ranges[0].source == new Source(SourceType.REQUEST_PARAMETER_NAME, paramName, null)

    where:
    paramName | _
    "param"   | _
  }
}
