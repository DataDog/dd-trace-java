package com.datadog.iast

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import com.datadog.iast.taint.TaintedObject
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class IastModuleImplOnStringConstructorTest extends IastModuleImplTestBase {
  IastRequestContext ctx = new IastRequestContext()

  void setup() {
    tracer.activeSpan() >> Mock(AgentSpan) {
      getRequestContext() >> Mock(RequestContext) {
        getData(RequestContextSlot.IAST) >> ctx
      }
    }
  }

  @SuppressWarnings('UnnecessaryStringInstantiation')
  void 'onStringConstructor with tainted argument'() {
    given:
    String arg = 'my value'
    String result = new String('my value')
    Range r = new Range(0, 5, new Source(SourceType.NONE, 'name', 'value'))
    ctx.taintedObjects.taint(arg, [r] as Range[])

    when:
    module.onStringConstructor(arg, result)
    TaintedObject to = ctx.taintedObjects.get(result)

    then:
    to != null
    to.ranges == [r]
  }

  void 'onStringConstructor with empty argument'() {
    given:
    String arg = ''
    String result = ''

    when:
    module.onStringConstructor(arg, result)
    TaintedObject to = ctx.taintedObjects.get(result)

    then:
    to == null
    0 * tracer.activeSpan()
  }
}
