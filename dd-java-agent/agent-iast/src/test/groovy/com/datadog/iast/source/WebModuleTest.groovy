package com.datadog.iast.source

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import com.datadog.iast.taint.Ranges
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.source.WebModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class WebModuleTest extends IastModuleImplTestBase {

  private WebModule module

  def setup() {
    module = registerDependencies(new WebModuleImpl())
  }

  void 'test #method: null or empty'(final String method, final String name) {
    when:
    module."$method"(name)

    then:
    0 * _

    where:
    method            | name
    'onParameterName' | null
    'onParameterName' | ''
    'onHeaderName'    | null
    'onHeaderName'    | ''
  }

  void 'test #method: without span'(final String method, final String name) {
    when:
    module."$method"(name)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    method            | name
    'onParameterName' | 'param'
    'onHeaderName'    | 'param'
  }

  void 'test #method'(final String method, final String name, final byte source) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    when:
    module."$method"(name)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(name)
    to != null
    to.get() == name
    to.ranges.size() == 1
    to.ranges[0].start == 0
    to.ranges[0].length == name.length()
    to.ranges[0].source == new Source(source, name, null)

    where:
    method            | name    | source
    'onParameterName' | 'param' | SourceType.REQUEST_PARAMETER_NAME
    'onHeaderName'    | 'param' | SourceType.REQUEST_HEADER_NAME
  }

  void 'test #method: null or empty'(final String method, final String name, final String value) {
    when:
    module."$method"(name, value)

    then:
    0 * _

    where:
    method             | name    | value
    'onParameterValue' | null    | null
    'onParameterValue' | null    | ""
    'onParameterValue' | ""      | null
    'onParameterValue' | ""      | ""
    'onParameterValue' | "param" | null
    'onParameterValue' | "param" | ""
    'onHeaderValue'    | null    | null
    'onHeaderValue'    | null    | ""
    'onHeaderValue'    | ""      | null
    'onHeaderValue'    | ""      | ""
    'onHeaderValue'    | "param" | null
    'onHeaderValue'    | "param" | ""
  }

  void 'test #method: without span'(final String method, final String name, final String value) {
    when:
    module."$method"(name, value)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    method             | name    | value
    'onParameterValue' | null    | "value"
    'onParameterValue' | ""      | "value"
    'onParameterValue' | "param" | "value"
    'onHeaderValue'    | null    | "value"
    'onHeaderValue'    | ""      | "value"
    'onHeaderValue'    | "param" | "value"
  }

  void 'test #method'(final String method, final String name, final String value, final byte source) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    when:
    module."$method"(name, value)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    ctx.getTaintedObjects().get(name) == null
    def to = ctx.getTaintedObjects().get(value)
    to != null
    to.get() == value
    to.ranges.size() == 1
    to.ranges[0].start == 0
    to.ranges[0].length == value.length()
    to.ranges[0].source == new Source(source, name, value)

    where:
    method             | name    | value   | source
    'onParameterValue' | null    | "value" | SourceType.REQUEST_PARAMETER_VALUE
    'onParameterValue' | ""      | "value" | SourceType.REQUEST_PARAMETER_VALUE
    'onParameterValue' | "param" | "value" | SourceType.REQUEST_PARAMETER_VALUE
    'onHeaderValue'    | null    | "value" | SourceType.REQUEST_HEADER_VALUE
    'onHeaderValue'    | ""      | "value" | SourceType.REQUEST_HEADER_VALUE
    'onHeaderValue'    | "param" | "value" | SourceType.REQUEST_HEADER_VALUE
  }

  void 'test onCookies without span'() {
    when:
    module.onCookies([new Object()])

    then:
    1 * tracer.activeSpan() >> null
    0 * _
  }

  void 'test onCookies null or empty'(final Object[] cookies) {
    when:
    module.onCookies(cookies)

    then:
    0 * _

    where:
    cookies << [null, []]
  }

  void 'test onCookies'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final Object cookie = new Object()

    when:
    module.onCookies(cookie)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _

    def to = ctx.getTaintedObjects().get(cookie)
    assert to != null
  }


  void 'test onCookieGetter without span'() {
    when:
    module.onCookieGetter(new Object(), null, "result", SourceType.REQUEST_COOKIE_NAME)

    then:
    1 * tracer.activeSpan() >> null
    0 * _
  }

  void 'test onCookieGetter null or empty'(final String value) {
    when:
    module.onCookieGetter(new Object(), 'cookieName', value, SourceType.REQUEST_COOKIE_NAME)

    then:
    0 * _

    where:
    value << [null, ""]
  }

  void 'test onCookieGetter'(final String value, final byte sourceTypeValue, final boolean isCookieTainted) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final Object cookie = new Object()
    if (isCookieTainted) {
      ctx.getTaintedObjects().taint(cookie, Ranges.EMPTY)
    }

    when:
    module.onCookieGetter(cookie, 'cookieName', value, sourceTypeValue)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _

    def to = ctx.getTaintedObjects().get(value)
    if (isCookieTainted && value != null && value != "") {
      assert to != null
      assert to.get() == value
      assert to.ranges.size() == 1
      assert to.ranges[0].start == 0
      assert to.ranges[0].length == value.length()
      assert to.ranges[0].source == new Source(sourceTypeValue, 'cookieName', value)
    } else {
      assert to == null
    }

    where:
    value     | sourceTypeValue                   | isCookieTainted
    "name"    | SourceType.REQUEST_COOKIE_NAME    | true
    "value"   | SourceType.REQUEST_COOKIE_VALUE   | true
    "comment" | SourceType.REQUEST_COOKIE_COMMENT | true
    "domain"  | SourceType.REQUEST_COOKIE_DOMAIN  | true
    "path"    | SourceType.REQUEST_COOKIE_PATH    | true
    "name"    | SourceType.REQUEST_COOKIE_NAME    | false
    "value"   | SourceType.REQUEST_COOKIE_VALUE   | false
    "comment" | SourceType.REQUEST_COOKIE_COMMENT | false
    "domain"  | SourceType.REQUEST_COOKIE_DOMAIN  | false
    "path"    | SourceType.REQUEST_COOKIE_PATH    | false
  }
}
