package com.datadog.iast


import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import org.junit.jupiter.api.Assertions

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplOnStringToUppercaseTest extends IastModuleImplTestBase {

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
    def result = self.toUpperCase()


    when:
    module.onStringToUpperCase(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString    | expected
    "a==>123<==b" | "A==>123<==B"
  }

  void 'test toUpperCase for not empty string cases'() {
    given:

    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.toUpperCase()


    when:
    module.onStringToUpperCase(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString    | expected
    "a==>123<==b" | "A==>123<==B"
    "a==>def<==b" | "A==>DEF<==B"
  }

  void 'test toUpperCase corner and pathologic cases'() {
    given:

    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.toUpperCase(new Locale(locale))


    when:
    module.onStringToUpperCase(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    self.size() == lengthSelf
    result.size() == lengthResult
    com.datadog.iast.model.Range[] ranges = taintedObject.getRanges()
    taintFormat(result, ranges) == expected
    ranges.size() == expectedRanges.size()

    System.out.println(String.format(" Unicode: %x ", "\u00cc".toLowerCase(new Locale("lt")).codePointAt(0)))
    for (Integer i = 0; i < ranges.size(); i++) {
      Assertions.assertEquals(ranges[i].getStart(), expectedRanges[i][0])
      Assertions.assertEquals(ranges[i].getLength(), expectedRanges[i][1])
    }

    where:
    testString                            | expected                    | locale | lengthSelf | lengthResult | expectedRanges
    "a==>123<==b"                         | "A==>123<==B"               | "en"   | 5          | 5            | [[1, 3]]
    "a==>def<==b"                         | "A==>DEF<==B"               | "en"   | 5          | 5            | [[1, 3]]
    "i̇̀==>def<==b"                       | "ÌD==>EFB<=="              | "lt"   | 7          | 6            | [[3, 3]]
    "i̇̀==>def<==b"                       | "İ̀==>DEF<==B"             | "en"   | 7          | 7            | [[3, 3]]
    "i̇̀==>def<==b==>def<=="              | "İ̀==>DEF<==B==>DEF<=="    | "en"   | 10         | 10           | [[3, 3], [7, 3]]
    "\u00cc==>def<==b"                    | "\u00cc==>DEF<==B"          | "lt"   | 5          | 5            | [[1, 3]]
    "i̇̀i̇̀==>fff<==f123b"                | "ÌÌFF==>FF123B<=="        | "lt"   | 14         | 12           | [[6, 6]]
    "i̇̀i̇̀i̇̀i̇̀EEEE==>fff<=="           | "ÌÌÌÌEEEEFFF"           | "lt"   | 19         | 15           | []
    "i̇̀i̇̀i̇̀i̇̀EEEE==>fff<==H==>GGG<==" | "ÌÌÌÌEEEEFFFH==>GGG<==" | "lt"   | 23         | 19           | [[16, 3]]
    "i̇̀i̇̀i̇̀EEEE==>fffgggg<=="          | "ÌÌÌEEEEFFF==>GGGG<=="   | "lt"   | 20         | 17           | [[13, 4]]
  }


  void 'test toLowerCase corner and pathologic cases'() {
    given:

    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.toLowerCase(new Locale(locale))


    when:
    module.onStringToLowerCase(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    self.size() == lengthSelf
    result.size() == lengthResult
    com.datadog.iast.model.Range[] ranges = taintedObject.getRanges()
    taintFormat(result, ranges) == expected
    ranges.size() == expectedRanges.size()

    System.out.println(String.format(" Unicode: %x ", "\u00cc".toLowerCase(new Locale("lt")).codePointAt(0)))
    for (Integer i = 0; i < ranges.size(); i++) {
      Assertions.assertEquals(ranges[i].getStart(), expectedRanges[i][0])
      Assertions.assertEquals(ranges[i].getLength(), expectedRanges[i][1])
    }

    where:
    testString                   | expected               | locale | lengthSelf | lengthResult | expectedRanges
    "A==>123<==B"                | "a==>123<==b"          | "en"   | 5          | 5            | [[1, 3]]
    "\u00cc\u00cc==>123<==B"     | "ìì==>123<==b"         | "en"   | 6          | 6            | [[2, 3]]
    "\u00cc\u00cc==>123<==B"     | "i̇==>̀i̇<==̀123b"     | "lt"   | 6          | 10           | [[2, 3]]
    "\u00cc\u00ccFFFF==>123<==B" | "i̇̀i̇̀==>fff<==f123b" | "lt"   | 10         | 14           | [[6, 3]]
    "A==>\u00cc\u00cc\u00cc<==B" | "a==>ììì<==b"          | "en"   | 5          | 5            | [[1, 3]]
  }
}
