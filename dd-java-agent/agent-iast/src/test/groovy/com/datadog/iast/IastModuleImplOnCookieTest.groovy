package com.datadog.iast

import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class IastModuleImplOnCookieTest extends IastModuleImplTestBase {

  void 'onCookie without span'() {
    when:
    module.onParameterName(cookieString)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    cookieString   | _
    "cookieString" | _
  }

  void 'onCookie'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    when:
    module.onCookie(cookieString)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(cookieString)
    to != null
    to.get() == cookieString
    to.ranges.size() == 1
    to.ranges[0].start == 0
    to.ranges[0].length == cookieString.length()
    to.ranges[0].source == new Source(SourceType.REQUEST_COOKIE, cookieString, null)

    where:
    cookieString    | _
    'cookieStrings' | _
  }
}
