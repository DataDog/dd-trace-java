package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.propagation.UrlModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.taint.TaintUtils.*

class UrlModuleTest extends IastModuleImplTestBase {

  private UrlModule module

  private List<Object> objectHolder

  def setup() {
    module = registerDependencies(new UrlModuleImpl())
    objectHolder = []
  }

  void 'onDecode (#value, #encoding)'(String value, final String encoding, final String expected) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
    final taintedObjects = ctx.getTaintedObjects()
    value = addFromTaintFormat(taintedObjects, value)
    objectHolder.add(value)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(result)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    module.onDecode(value, encoding, result)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(result)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    value                                                                                                  | encoding | expected
    'https%3A%2F%2Fdatadoghq.com%2Faccount%2Flogin%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches'       | null     | 'https://datadoghq.com/account/login?next=/ci/test-services?view=branches'
    'https%3A%2F%2Fdatadoghq.com%2Faccount%2Flogin%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches'       | 'utf-8'  | 'https://datadoghq.com/account/login?next=/ci/test-services?view=branches'
    '==>https%3A%2F%2Fdatadoghq.com%2Faccount%2Flogin%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches<==' | null     | '==>https://datadoghq.com/account/login?next=/ci/test-services?view=branches<=='
    '==>https%3A%2F%2Fdatadoghq.com%2Faccount%2Flogin%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches<==' | 'utf-8'  | '==>https://datadoghq.com/account/login?next=/ci/test-services?view=branches<=='
    'https%3A%2F%2Fdatadoghq.com%2Faccount%2F==>login<==%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches' | null     | '==>https://datadoghq.com/account/login?next=/ci/test-services?view=branches<=='
    'https%3A%2F%2Fdatadoghq.com%2Faccount%2F==>login<==%3Fnext%3D%2Fci%2Ftest-services%3Fview%3Dbranches' | 'utf-8'  | '==>https://datadoghq.com/account/login?next=/ci/test-services?view=branches<=='
  }
}
