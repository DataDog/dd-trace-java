package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.propagation.StringModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplOnStringTrimTest extends IastModuleImplTestBase {
  private StringModule module


  def setup() {
    module = registerDependencies(new StringModuleImpl())
  }

  void 'make sure IastRequestContext is called'() {
    given:

    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.trim()


    when:
    module.onStringTrim(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString     | expected
    "==>123<==   " | "==>123<=="
  }

  void 'test trim for not empty string cases'() {
    given:

    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.trim()


    when:
    module.onStringTrim(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString                                                     | expected
    " ==>   <== ==>   <== ==>456<== ==>ABC<== ==>   <== ==>   <==" | "==>456<== ==>ABC<=="
    " ==>   <== ==>   <== ==>456<== ==>   <== ==>   <=="           | "==>456<=="
    " ==>   <== ==>   <== ==>456<== ==>   <== "                    | "==>456<=="
    " ==>   <== ==>   <== ==>456<== "                              | "==>456<=="
    "==>   <== ==>   <== ==>456<== "                               | "==>456<=="
    "==>ABC<==123==>456<== "                                       | "==>ABC<==123==>456<=="
    "==>ABC<==123   ==>   <== "                                    | "==>ABC<==123"
    "==>ABC<==   ==>   <== "                                       | "==>ABC<=="
    "==>ABC<==   ==>   <=="                                        | "==>ABC<=="
    " ==>   <== 789==>456<== "                                     | "789==>456<=="
    "==>   <== 789==>456<== "                                      | "789==>456<=="
    "==>   <== ==>456<== "                                         | "==>456<=="
    "==>   <== ==>456<=="                                          | "==>456<=="
    "==>   <====>456<=="                                           | "==>456<=="
    "==>123<=="                                                    | "==>123<=="
    "   ==>123<=="                                                 | "==>123<=="
    "==>123<==   "                                                 | "==>123<=="
  }

  void 'test trim for empty string cases'() {
    given:

    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.trim()


    when:
    module.onStringTrim(self, result)

    then:
    null == taintedObjects.get(result)
    result == expected

    where:
    testString              | expected
    " ==>   <== "           | ""
    ""                      | ""
    " ==>   <== ==>   <== " | ""
    "==>   <== ==>   <=="   | ""
    "123"                   | "123"
    " 123 "                 | "123"
  }
}
