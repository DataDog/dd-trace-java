package com.datadog.iast.source

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.source.WebModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class WebModuleTest extends IastModuleImplTestBase {

  private WebModule module

  def setup() {
    module = registerDependencies(new WebModuleImpl())
  }

  void 'onParameterName null or empty'(final String paramName) {
    when:
    module.onParameterName(paramName)

    then:
    0 * _

    where:
    paramName | _
    null      | _
    ""        | _
  }

  void 'onParameterName without span'(final String paramName) {
    when:
    module.onParameterName(paramName)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    paramName | _
    "param"   | _
  }

  void 'onParameterName'(final String paramName) {
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

  void 'onParameterValue null or empty'(final String paramName, final String paramValue) {
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

  void 'onParameterValue without span'(final String paramName, final String paramValue) {
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

  void 'onParameterValue'(final String paramName, final String paramValue) {
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
