package com.datadog.iast

import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class IastModuleImplOnCookieTest extends IastModuleImplTestBase {

  void 'onCookie without span'() {
    when:
    module.onCookie("comment", "domain", "value", "name", "path")

    then:
    1 * tracer.activeSpan() >> null
    0 * _
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
    module.onCookie(comment, domain, value, name, path)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    checkCookieStringAttr(ctx, "comment", comment)
    checkCookieStringAttr(ctx, "value", value)
    checkCookieStringAttr(ctx, "domain", domain)
    checkCookieStringAttr(ctx, "name", name)
    checkCookieStringAttr(ctx, "path", path)

    where:
    comment   | domain   | value   | name   | path
    "comment" | "domain" | "value" | "name" | "path"
  }

  void 'checkCookieStringAttr'(ctx, attr, value) {
    def to = ctx.getTaintedObjects().get(value)
    if (value != null && value != "") {
      assert to != null
      assert to.get() == value
      assert to.ranges.size() == 1
      assert to.ranges[0].start == 0
      assert to.ranges[0].length == value.length()
      assert to.ranges[0].source == new Source(SourceType.REQUEST_COOKIE, "http.request.cookie." + attr, value)
    } else {
      assert to == null
    }
  }
}
