package com.datadog.iast.taint

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Source
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class TaintedObjectsLazyTest extends IastModuleImplTestBase {

  private TaintedObjects delegate = Mock(TaintedObjects)
  private IastRequestContext iastCtx
  private RequestContext reqCtx
  private AgentSpan span

  void setup() {
    delegate = Mock(TaintedObjects)
    iastCtx = Mock(IastRequestContext)
    iastCtx.getTaintedObjects() >> delegate
    reqCtx = Mock(RequestContext)
    reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    span = Mock(AgentSpan)
    span.getRequestContext() >> reqCtx
  }

  void 'get non lazy instance'() {
    when:
    final to = TaintedObjects.activeTaintedObjects()

    then:
    1 * tracer.activeSpan() >> span
    !(to instanceof TaintedObjects.LazyTaintedObjects)
  }

  void 'get lazy objects instance'() {
    when:
    final to = TaintedObjects.activeTaintedObjects(true)

    then:
    to instanceof TaintedObjects.LazyTaintedObjects

    when:
    to.&"$method".call(args as Object[])

    then: 'first time the active tainted objects if fetched'
    1 * delegate._
    1 * tracer.activeSpan() >> span

    when:
    to.&"$method".call(args as Object[])

    then: 'the active tainted objets is already fetched'
    1 * delegate._
    0 * _

    where:
    method             | args
    'getEstimatedSize' | []
    'isFlat'           | []
    'taintInputString' | ['', new Source((byte) 0, null, null)]
    'taintInputObject' | ['', new Source((byte) 0, null, null)]
    'taint'            | ['', Ranges.EMPTY]
    'get'              | ['']
    'release'          | []
    'count'            | []
    'iterator'         | []
  }
}
