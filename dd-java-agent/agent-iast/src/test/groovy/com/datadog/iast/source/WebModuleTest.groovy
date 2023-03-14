package com.datadog.iast.source

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Source
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.source.WebModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic

@CompileDynamic
class WebModuleTest extends IastModuleImplTestBase {

  private WebModule module

  def setup() {
    module = new WebModuleImpl()
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
    'onParameterName' | 'param' | SourceTypes.REQUEST_PARAMETER_NAME
    'onHeaderName'    | 'param' | SourceTypes.REQUEST_HEADER_NAME
  }

  void 'test #method: null or empty'(final String method, final String name, final String value) {
    when:
    module."$method"(name, value)

    then:
    0 * _

    where:
    method                   | name    | value
    'onParameterValue'       | null    | null
    'onParameterValue'       | null    | ""
    'onParameterValue'       | ""      | null
    'onParameterValue'       | ""      | ""
    'onParameterValue'       | "param" | null
    'onParameterValue'       | "param" | ""
    'onHeaderValue'          | null    | null
    'onHeaderValue'          | null    | ""
    'onHeaderValue'          | ""      | null
    'onHeaderValue'          | ""      | ""
    'onHeaderValue'          | "param" | null
    'onHeaderValue'          | "param" | ""
    'onRequestPathParameter' | null    | null
    'onRequestPathParameter' | null    | ''
    'onRequestPathParameter' | 'param' | null
    'onRequestPathParameter' | 'param' | ''
  }

  void 'test #method: without span'(final String method, final String name, final String value) {
    when:
    module."$method"(name, value)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    method                   | name    | value
    'onParameterValue'       | null    | "value"
    'onParameterValue'       | ""      | "value"
    'onParameterValue'       | "param" | "value"
    'onHeaderValue'          | null    | "value"
    'onHeaderValue'          | ""      | "value"
    'onHeaderValue'          | "param" | "value"
    'onRequestPathParameter' | 'param' | 'value'
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
    method                   | name    | value   | source
    'onParameterValue'       | null    | "value" | SourceTypes.REQUEST_PARAMETER_VALUE
    'onParameterValue'       | ""      | "value" | SourceTypes.REQUEST_PARAMETER_VALUE
    'onParameterValue'       | "param" | "value" | SourceTypes.REQUEST_PARAMETER_VALUE
    'onHeaderValue'          | null    | "value" | SourceTypes.REQUEST_HEADER_VALUE
    'onHeaderValue'          | ""      | "value" | SourceTypes.REQUEST_HEADER_VALUE
    'onHeaderValue'          | "param" | "value" | SourceTypes.REQUEST_HEADER_VALUE
    'onRequestPathParameter' | ''      | 'value' | SourceTypes.REQUEST_PATH_PARAMETER
    'onRequestPathParameter' | 'param' | 'value' | SourceTypes.REQUEST_PATH_PARAMETER
  }

  void 'test onQueryString without span'() {
    when:
    module.onQueryString('query string')

    then:
    1 * tracer.activeSpan() >> null
    0 * _
  }

  void 'test onQueryString with null or empty query string'() {
    when:
    module.onQueryString(value)

    then:
    0 * _

    where:
    value | _
    null  | _
    ''    | _
  }

  void 'test onQueryString'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final value = 'key=value'

    when:
    module.onQueryString(value)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _

    final tainted = ctx.getTaintedObjects().get(value)
    tainted != null
    tainted.ranges.length == 1
    tainted.ranges.first().source.origin == SourceTypes.REQUEST_QUERY
  }
}
