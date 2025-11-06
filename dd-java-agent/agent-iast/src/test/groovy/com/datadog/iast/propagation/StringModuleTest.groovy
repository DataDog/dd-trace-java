package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.StringModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Assertions
import spock.lang.IgnoreIf

import java.text.SimpleDateFormat

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taint
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static com.datadog.iast.taint.TaintUtils.taintObject

@CompileDynamic
class StringModuleTest extends IastModuleImplTestBase {

  private StringModule module

  def setup() {
    module = new StringModuleImpl()
  }

  @Override
  protected AgentTracer.TracerAPI buildAgentTracer() {
    return Mock(AgentTracer.TracerAPI) {
      activeSpan() >> span
      getTraceSegment() >> traceSegment
    }
  }

  @Override
  protected RequestContext buildRequestContext() {
    return Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
      getTraceSegment() >> traceSegment
    }
  }

  @Override
  protected AgentSpan buildAgentSpan() {
    return Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
  }

  void 'onStringBuilderAppend null or empty (#builder, #param)'() {
    given:
    final result = builder?.append(param)

    when:
    module.onStringBuilderAppend(result, param)

    then:
    0 * _

    where:
    builder | param
    sb('')  | null
    sb('')  | ''
  }

  void 'onStringBuilderAppend without span (#builder, #param)'() {
    given:
    final result = builder?.append(param)

    when:
    module.onStringBuilderAppend(result, param)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    builder | param | mockCalls
    sb('1') | null  | 0
    sb('3') | '4'   | 1
  }

  void 'onStringBuilderAppend (#builder, #param)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    builder = addFromTaintFormat(taintedObjects, builder)
    objectHolder.add(builder)
    param = addFromTaintFormat(taintedObjects, param)
    objectHolder.add(param)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(result)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    builder = builder?.append(param)
    module.onStringBuilderAppend(builder, param)

    then:
    mockCalls * tracer.activeSpan() >> span
    mockCalls * span.getRequestContext() >> reqCtx
    mockCalls * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(builder)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() as String == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    builder                     | param                   | mockCalls | expected
    sb('123')                   | null                    | 0         | '123null'
    sb('123')                   | '456'                   | 1         | '123456'
    sb('==>123<==')             | null                    | 0         | '==>123<==null'
    sb('==>123<==')             | '456'                   | 1         | '==>123<==456'
    sb('123')                   | '==>456<=='             | 1         | '123==>456<=='
    sb('==>123<==')             | '==>456<=='             | 1         | '==>123<====>456<=='
    sb('1==>234<==5==>678<==9') | 'a==>bcd<==e'           | 1         | '1==>234<==5==>678<==9a==>bcd<==e'
    sb('1==>234<==5==>678<==9') | 'a==>bcd<==e==>fgh<==i' | 1         | '1==>234<==5==>678<==9a==>bcd<==e==>fgh<==i'
  }

  void 'onStringBuilderAppend null or empty (#builder, #param, #start, #end)'() {
    given:
    final result = builder?.append(param, start, end)

    when:
    module.onStringBuilderAppend(result, param, start, end)

    then:
    0 * _

    where:
    builder | param | start | end
    sb('')  | null  | 0     | 0
    sb('')  | ''    | 0     | 0
  }

  void 'onStringBuilderAppend without span (#builder, #param, #start, #end)'() {
    given:
    final result = builder?.append(param, start, end)

    when:
    module.onStringBuilderAppend(result, param)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    builder | param | start | end | mockCalls
    sb('1') | null  | 0     | 0   | 0
    sb('3') | '4'   | 0     | 0   | 1
  }

  void 'onStringBuilderAppend (#builder, #param, #start, #end)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def builderTainted = addFromTaintFormat(taintedObjects, builder)
    objectHolder.add(builderTainted)
    def paramTainted = addFromTaintFormat(taintedObjects, param)
    objectHolder.add(paramTainted)
    builderTainted?.append(paramTainted, start, end)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(result)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    module.onStringBuilderAppend(builderTainted, paramTainted, start, end)
    def taintedObject = taintedObjects.get(builderTainted)

    then:
    if (shouldBeTainted) {
      assert taintedObject != null
      assert taintedObject.get() as String == result
      assert taintFormat(taintedObject.get() as String, taintedObject.getRanges()) == expected
    } else {
      assert taintedObject == null
    }

    where:
    builder                     | param                   | start | end | expected
    sb('123')                   | '456'                   | 0     | 3   | '123456'
    sb('==>123<==')             | '456'                   | 0     | 3   | '==>123<==456'
    sb('==>123<==')             | '456'                   | 1     | 3   | '==>123<==56'
    sb('123')                   | '==>456<=='             | 0     | 3   | '123==>456<=='
    sb('123')                   | '==>456<=='             | 1     | 2   | '123==>5<=='
    sb('==>123<==')             | '==>456<=='             | 0     | 3   | '==>123<====>456<=='
    sb('1==>234<==5==>678<==9') | 'a==>bcd<==e'           | 0     | 5   | '1==>234<==5==>678<==9a==>bcd<==e'
    sb('1==>234<==5==>678<==9') | 'a==>bcd<==e==>fgh<==i' | 0     | 9   | '1==>234<==5==>678<==9a==>bcd<==e==>fgh<==i'
    sb('1==>234<==5==>678<==9') | 'a==>bcd<==e==>fgh<==i' | 5     | 9   | '1==>234<==5==>678<==9==>fgh<==i'
    sb('1==>234<==5==>678<==9') | 'a==>bcd<==e==>fgh<==i' | 5     | 8   | '1==>234<==5==>678<==9==>fgh<=='
  }

  void 'onStringBuilderInit null or empty (#builder, #param)'() {
    given:
    final result = builder?.append(param)

    when:
    module.onStringBuilderInit(result, param)

    then:
    0 * _

    where:
    builder | param
    sb('')  | null
    sb('')  | ''
  }

  void 'onStringBuilderInit without span (#builder, #param)'() {
    given:
    final result = builder?.append(param)

    when:
    module.onStringBuilderInit(result, param)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    builder | param | mockCalls
    sb()    | null  | 0
    sb()    | '4'   | 1
  }

  void 'onStringBuilderInit (#builder, #param)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    builder = addFromTaintFormat(taintedObjects, builder)
    objectHolder.add(builder)
    param = addFromTaintFormat(taintedObjects, param)
    objectHolder.add(param)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(result)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    builder = builder?.append(param)
    module.onStringBuilderInit(builder, param)

    then:
    mockCalls * tracer.activeSpan() >> span
    mockCalls * span.getRequestContext() >> reqCtx
    mockCalls * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(builder)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() as String == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    builder | param                   | mockCalls | expected
    sb()    | null                    | 0         | 'null'
    sb()    | '123'                   | 1         | '123'
    sb()    | '==>123<=='             | 1         | '==>123<=='
    sb()    | 'a==>bcd<==e==>fgh<==i' | 1         | 'a==>bcd<==e==>fgh<==i'
  }

  void 'onStringBuilderToString without span'() {
    given:
    def builder = sb('1')
    final result = builder.toString()

    when:
    module.onStringBuilderToString(builder, result)

    then:
    1 * tracer.activeSpan() >> null
    0 * _
  }

  void 'onStringBuilderToString (#builder)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    builder = addFromTaintFormat(taintedObjects, builder)
    objectHolder.add(builder)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(result)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    final toString = builder?.toString()
    module.onStringBuilderToString(builder, toString)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(builder)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() as String == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    builder            | expected
    sb('123')          | '123'
    sb('==>123<==')    | '==>123<=='
    sb('==>123<==456') | '==>123<==456'
  }

  void 'onStringConcatFactory null or empty (#args)'() {
    given:
    final result = args.inject('') { res, item -> res + item }

    when:
    module.onStringConcatFactory(result, args as String[], recipe, constants as Object[], recipeOffsets as int[])

    then:
    0 * _

    where:
    args         | recipe          | constants | recipeOffsets
    null         | '\u0001'        | null      | [0]
    ['']         | '\u0001'        | null      | [0]
    ['', null]   | '\u0001 \u0001' | null      | [0, -1, 1]
    [null, '']   | '\u0001 \u0001' | null      | [0, -1, 1]
    [null, null] | '\u0001 \u0001' | null      | [0, -1, 1]
    ['', '']     | '\u0001 \u0001' | null      | [0, -1, 1]
  }

  void 'onStringConcatFactory without span (#args)'() {
    given:
    final result = args.inject('') { res, item -> res + item }

    when:
    module.onStringConcatFactory(result, args as String[], recipe, constants as Object[], recipeOffsets as int[])

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    args        | recipe          | constants | recipeOffsets
    ['1', null] | '\u0001 \u0001' | null      | [0, -1, 1]
    [null, '2'] | '\u0001 \u0001' | null      | [0, -1, 1]
    ['3', '4']  | '\u0001 \u0001' | null      | [0, -1, 1]
  }

  void 'onStringConcatFactory (#args, #recipe, #constants)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    args = args.collect {
      final item = addFromTaintFormat(taintedObjects, it)
      objectHolder.add(item)
      return item
    }

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    module.onStringConcatFactory(result, args as String[], recipe, constants as Object[], recipeOffsets as int[])

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
    args                                       | recipe                             | constants               | recipeOffsets      | expected
    ['123', null]                              | '\u0001\u0001'                     | []                      | [0, 1]             | '123null'
    [null, '123']                              | '\u0001\u0001'                     | []                      | [0, 1]             | 'null123'
    ['123', '456']                             | '\u0001\u0001'                     | []                      | [0, 1]             | '123456'
    ['==>123<==', null]                        | '\u0001\u0001'                     | []                      | [0, 1]             | '==>123<==null'
    [null, '==>123<==']                        | '\u0001\u0001'                     | []                      | [0, 1]             | 'null==>123<=='
    ['==>123<==', '456']                       | '\u0001\u0001'                     | []                      | [0, 1]             | '==>123<==456'
    ['123', '==>456<==']                       | '\u0001\u0001'                     | []                      | [0, 1]             | '123==>456<=='
    ['==>123<==', '==>456<==']                 | '\u0001\u0001'                     | []                      | [0, 1]             | '==>123<====>456<=='
    ['123', null]                              | '\u0002\u0001\u0002\u0001'         | ['\u0001 ', ' \u0002 '] | [-2, 0, -3, 1]     | '\u0001 123 \u0002 null'
    [null, '123']                              | '\u0002\u0001\u0002\u0001'         | ['\u0001 ', ' \u0002 '] | [-2, 0, -3, 1]     | '\u0001 null \u0002 123'
    ['123', '456']                             | '\u0002\u0001\u0002\u0001'         | ['\u0001 ', ' \u0002 '] | [-2, 0, -3, 1]     | '\u0001 123 \u0002 456'
    ['==>123<==', null]                        | '\u0002\u0001\u0002\u0001'         | ['\u0001 ', ' \u0002 '] | [-2, 0, -3, 1]     | '\u0001 ==>123<== \u0002 null'
    [null, '==>123<==']                        | '\u0002\u0001\u0002\u0001'         | ['\u0001 ', ' \u0002 '] | [-2, 0, -3, 1]     | '\u0001 null \u0002 ==>123<=='
    ['==>123<==', '456']                       | '\u0002\u0001\u0002\u0001'         | ['\u0001 ', ' \u0002 '] | [-2, 0, -3, 1]     | '\u0001 ==>123<==4 \u0002 56'
    ['123', '==>456<==']                       | '\u0002\u0001\u0002\u0001'         | ['\u0001 ', ' \u0002 '] | [-2, 0, -3, 1]     | '\u0001 123 \u0002 ==>456<=='
    ['==>123<==', '==>456<==']                 | '\u0002\u0001\u0002\u0001'         | ['\u0001 ', ' \u0002 '] | [-2, 0, -3, 1]     | '\u0001 ==>123<== \u0002 ==>456<=='
    ['He==>llo<==', '==> W<==or==>ld<==']      | '\u0001\u0001'                     | []                      | [0, 1]             | 'He==>llo<====> W<==or==>ld<=='
    ['He==>llo<==', '==>W<==or==>ld<==']       | '\u0001 \u0001'                    | []                      | [0, -1, 1]         | 'He==>llo<== ==>W<==or==>ld<=='
    ['He==>l\u0001lo<==', '==>Wor\u0002<==ld'] | '\u0001 \u0001'                    | []                      | [0, -1, 1]         | 'He==>l\u0001lo<== ==>Wor\u0002<==ld'
    ['==>one<==', '==>three<==', '==>five<=='] | '\u0001 two \u0001 four \u0001'    | []                      | [0, -5, 1, -6, 2]  | '==>one<== two ==>three<== four ==>five<=='
    ['==>two<==', '==>four<==']                | 'one \u0001 three \u0001 five'     | []                      | [-4, 0, -7, 1, -5] | 'one ==>two<== three ==>four<== five'
    ['==>one<==', '==>three<==', '==>five<=='] | '\u0001 \u0002 \u0001 four \u0001' | ['\u0001two\u0001']     | [0, -7, 1, -6, 2]  | '==>one<== \u0001two\u0001 ==>three<== four ==>five<=='
    ['==>one<==', '==>three<==', '==>five<=='] | '\u0001\u0002\u0001𠆢four𠆢\u0001'   | ['\u0001two𠆢']          | [0, -6, 1, -8, 2]  | '==>one<==\u0001two𠆢==>three<==𠆢four𠆢==>five<=='
  }

  void 'onStringConcat null or empty (#left, #right)'() {
    given:
    final result = left + right

    when:
    module.onStringConcat(left, right, result)

    then:
    0 * _

    where:
    left | right
    ""   | null
    ""   | ""
  }

  void 'onStringConcat without span (#left, #right)'() {
    given:
    final result = left + right

    when:
    module.onStringConcat(left, right, result)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    left | right
    "1"  | null
    "3"  | "4"
  }

  void 'onStringConcat (#left, #right)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    left = addFromTaintFormat(taintedObjects, left)
    objectHolder.add(left)
    right = addFromTaintFormat(taintedObjects, right)
    objectHolder.add(right)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(result) != null

    when:
    module.onStringConcat(left, right, expected)

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
    left        | right       | expected
    "123"       | null        | "123null"
    "123"       | "456"       | "123456"
    "==>123<==" | null        | "==>123<==null"
    "==>123<==" | "456"       | "==>123<==456"
    "123"       | "==>456<==" | "123==>456<=="
    "==>123<==" | "==>456<==" | "==>123<====>456<=="
  }

  void 'onStringSubSequence null ,empty or string not changed after subsequence (#self, #beginIndex, #endIndex)'() {
    given:
    final result = self?.substring(beginIndex, endIndex)

    when:
    module.onStringSubSequence(self, beginIndex, endIndex, result)

    then:
    0 * _

    where:
    self          | beginIndex | endIndex
    ""            | 0          | 0
    null          | 0          | 0
    "not_changed" | 0          | 11
  }

  void 'onStringSubSequence without span (#self, #beginIndex, #endIndex)'() {
    given:
    final result = self?.substring(beginIndex, endIndex)

    when:
    module.onStringSubSequence(self, beginIndex, endIndex, result)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    self  | beginIndex | endIndex | mockCalls
    ""    | 0          | 0        | 0
    null  | 0          | 0        | 0
    "123" | 1          | 2        | 1
  }

  void 'onStringSubSequence (#self, #beginIndex, #endIndex)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    self = addFromTaintFormat(taintedObjects, self)
    objectHolder.add(self)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    module.onStringSubSequence(self, beginIndex, endIndex, result)

    then:
    assert result == getStringFromTaintFormat(self).substring(beginIndex, endIndex)
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
    self                           | beginIndex | endIndex | expected
    "==>0123<=="                   | 0          | 4        | "==>0123<=="
    "0123==>456<==78"              | 0          | 5        | "0123==>4<=="
    "01==>234<==5==>678<==90"      | 0          | 8        | "01==>234<==5==>67<=="
    "==>0123<=="                   | 0          | 3        | "==>012<=="
    "==>0123<=="                   | 1          | 4        | "==>123<=="
    "==>0123<=="                   | 1          | 3        | "==>12<=="
    "0123==>456<==78"              | 1          | 8        | "123==>456<==7"
    "0123==>456<==78"              | 0          | 4        | "0123"
    "0123==>456<==78"              | 7          | 9        | "78"
    "0123==>456<==78"              | 1          | 5        | "123==>4<=="
    "0123==>456<==78"              | 1          | 6        | "123==>45<=="
    "0123==>456<==78"              | 4          | 7        | "==>456<=="
    "0123==>456<==78"              | 6          | 8        | "==>6<==7"
    "0123==>456<==78"              | 5          | 8        | "==>56<==7"
    "0123==>456<==78"              | 4          | 6        | "==>45<=="
    "01==>234<==5==>678<==90"      | 1          | 10       | "1==>234<==5==>678<==9"
    "01==>234<==5==>678<==90"      | 1          | 2        | "1"
    "01==>234<==5==>678<==90"      | 5          | 6        | "5"
    "01==>234<==5==>678<==90"      | 9          | 10       | "9"
    "01==>234<==5==>678<==90"      | 1          | 4        | "1==>23<=="
    "01==>234<==5==>678<==90"      | 2          | 4        | "==>23<=="
    "01==>234<==5==>678<==90"      | 2          | 5        | "==>234<=="
    "01==>234<==5==>678<==90"      | 1          | 8        | "1==>234<==5==>67<=="
    "01==>234<==5==>678<==90"      | 2          | 8        | "==>234<==5==>67<=="
    "01==>234<==5==>678<==90"      | 2          | 9        | "==>234<==5==>678<=="
    "01==>234<==5==>678<==90"      | 5          | 8        | "5==>67<=="
    "01==>234<==5==>678<==90"      | 6          | 8        | "==>67<=="
    "01==>234<==5==>678<==90"      | 6          | 9        | "==>678<=="
    "01==>234<==5==>678<==90"      | 4          | 9        | "==>4<==5==>678<=="
    "01==>234<==5==>678<==90"      | 4          | 8        | "==>4<==5==>67<=="
    sb("==>0123<==")               | 0          | 4        | "==>0123<=="
    sb("0123==>456<==78")          | 0          | 5        | "0123==>4<=="
    sb("01==>234<==5==>678<==90")  | 0          | 8        | "01==>234<==5==>67<=="
    sb("0123==>456<==78")          | 4          | 6        | "==>45<=="
    sb("01==>234<==5==>678<==90")  | 4          | 8        | "==>4<==5==>67<=="
    sbf("==>0123<==")              | 0          | 4        | "==>0123<=="
    sbf("0123==>456<==78")         | 0          | 5        | "0123==>4<=="
    sbf("01==>234<==5==>678<==90") | 0          | 8        | "01==>234<==5==>67<=="
    sbf("0123==>456<==78")         | 4          | 6        | "==>45<=="
    sbf("01==>234<==5==>678<==90") | 4          | 8        | "==>4<==5==>67<=="
  }

  void 'onStringJoin without null delimiter or elements (#delimiter, #elements)'() {
    when:
    String.join(delimiter, elements)

    then:
    thrown(NullPointerException)

    where:
    delimiter | elements
    null      | ["123", "456"]
    ""        | null
    null      | null
  }

  void 'onStringJoin without span (#delimiter, #elements)'(final CharSequence delimiter, final CharSequence[] elements, final int mockCalls) {
    given:
    final result = String.join(delimiter, elements)

    when:
    module.onStringJoin(result, delimiter, elements)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    delimiter              | elements                                             | mockCalls
    ""                     | ["123", "456"]                                       | 1
    "-"                    | ["123", "456"]                                       | 1
    ""                     | []                                                   | 0
    "-"                    | []                                                   | 0
    ""                     | [new StringBuilder("123"), new StringBuilder("456")] | 1
    "-"                    | [new StringBuilder("123"), new StringBuilder("456")] | 1
    new StringBuilder()    | ["123", "456"]                                       | 1
    new StringBuilder("-") | ["123", "456"]                                       | 1
    new StringBuilder()    | []                                                   | 0
    new StringBuilder("-") | []                                                   | 0
    new StringBuilder("")  | [new StringBuilder("123"), new StringBuilder("456")] | 1
    new StringBuilder("-") | [new StringBuilder("123"), new StringBuilder("456")] | 1
  }

  void 'onStringJoin (#delimiter, #elements)'() {
    given:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(expected) != null

    and:
    final taintedObjects = ctx.getTaintedObjects()
    final fromTaintedDelimiter = addFromTaintFormat(taintedObjects, delimiter)
    objectHolder.add(fromTaintedDelimiter)

    and:
    final fromTaintedElements = new CharSequence[elements.size()]
    elements.eachWithIndex { element, i ->
      def el = addFromTaintFormat(taintedObjects, element)
      objectHolder.add(el)
      fromTaintedElements[i] = el
    }

    when:
    module.onStringJoin(result, fromTaintedDelimiter, fromTaintedElements)

    then:
    assert result == String.join(fromTaintedDelimiter, fromTaintedElements)
    1 * tracer.activeSpan() >> span
    def to = ctx.getTaintedObjects().get(result)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    delimiter              | elements                                                                                    | expected
    " "                    | ["==>Hello<==", null, "==>World<=="]                                                        | "==>Hello<== null ==>World<=="
    " "                    | ["==>Hello<==", null, "a", "==>b<==", "c"]                                                  | "==>Hello<== null a ==>b<== c"
    ""                     | ["0==>12<==3", "456", "7==>89<=="]                                                          | "0==>12<==34567==>89<=="
    "-"                    | ["0==>12<==3", "456", "7==>89<=="]                                                          | "0==>12<==3-456-7==>89<=="
    "-"                    | [new StringBuilder("0==>12<==3"), new StringBuilder("456"), new StringBuilder("7==>89<==")] | "0==>12<==3-456-7==>89<=="
    new StringBuilder("-") | ["0==>12<==3", "456", "7==>89<=="]                                                          | "0==>12<==3-456-7==>89<=="
    new StringBuilder("-") | [new StringBuilder("0==>12<==3"), new StringBuilder("456"), new StringBuilder("7==>89<==")] | "0==>12<==3-456-7==>89<=="
    "-"                    | ["0123", "456", "789"]                                                                      | "0123-456-789"
    "==>TAINTED<=="        | ["0123", "456", "789"]                                                                      | "0123==>TAINTED<==456==>TAINTED<==789"
    ", "                   | ["untainted", null]                                                                         | "untainted, null"
    ", "                   | ["untainted", "another"]                                                                    | "untainted, another"
    ", "                   | ["stringParam==>taintedString<==", "another"]                                               | "stringParam==>taintedString<==, another"
    ", "                   | ["stringParam==>taintedString<==", null]                                                    | "stringParam==>taintedString<==, null"
    ", "                   | ["stringParam:another", "==>taintedString<=="]                                              | "stringParam:another, ==>taintedString<=="
    ", "                   | [
      "stringParam1,stringParam2,stringParam3:==>taintedString<==",
      "==>taintedString<==",
      "==>taintedString<=="
    ]                                                                                                                    | "stringParam1,stringParam2,stringParam3:==>taintedString<==, ==>taintedString<==, ==>taintedString<=="
  }

  void 'onStringRepeat that can not be tainted (#self, #count)'() {
    when:
    module.onStringRepeat(self, count, expected)

    then:
    0 * _

    where:
    self  | count | expected
    ""    | 1     | ""
    "abc" | 0     | ""
    null  | 1     | ""
    null  | 0     | ""
    "abc" | 1     | "abc"
  }

  void 'onStringRepeat without span (#self, #count)'(final String self, final int count, final String expected, final int mockCalls) {
    when:
    module.onStringRepeat(self, count, expected)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    self  | count | expected | mockCalls
    ""    | 0     | ""       | 0
    null  | 0     | ""       | 0
    ""    | 1     | ""       | 0
    null  | 1     | ""       | 0
    "abc" | 1     | 'abc'    | 0
    "abc" | 2     | 'abcabc' | 1
  }

  void 'onStringRepeat (#self, #count)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    self = addFromTaintFormat(taintedObjects, self)
    objectHolder.add(self)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    module.onStringRepeat(self, count, result)

    then:
    1 * tracer.activeSpan() >> span
    def to = ctx.getTaintedObjects().get(result)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    self                | count | expected
    "abc"               | 2     | "abcabc"
    "==>b<=="           | 2     | "==>b<====>b<=="
    "aa==>b<=="         | 2     | "aa==>b<==aa==>b<=="
    "==>b<==cc"         | 2     | "==>b<==cc==>b<==cc"
    "a==>b<==c"         | 2     | "a==>b<==ca==>b<==c"
    "a==>b<==c==>d<==e" | 2     | "a==>b<==c==>d<==ea==>b<==c==>d<==e"
  }

  void 'test toUpperCase for null arguments'() {
    when:
    module.onStringToUpperCase(null, null)

    then:
    noExceptionThrown()
  }

  void 'onStringToUpperCase calls IastRequestContext'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.toUpperCase()


    when:
    module.onStringToUpperCase(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString    | expected
    "a==>123<==b" | "A==>123<==B"
  }

  void 'test toUpperCase for not empty string cases'() {
    given:
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

    for (Integer i = 0; i < ranges.size(); i++) {
      Assertions.assertEquals(ranges[i].getStart(), expectedRanges[i][0])
      Assertions.assertEquals(ranges[i].getLength(), expectedRanges[i][1])
    }

    where:
    testString                            | expected                    | locale | lengthSelf | lengthResult | expectedRanges
    "==>ab<=="                            | "==>AB<=="                  | "en"   | 2          | 2            | [[0, 2]]
    "a==>123<==b==>123<==c"               | "A==>123<==B==>123<==C"     | "en"   | 9          | 9            | [[1, 3], [5, 3]]
    "a==>123<==b"                         | "A==>123<==B"               | "en"   | 5          | 5            | [[1, 3]]
    "a==>def<==b"                         | "A==>DEF<==B"               | "en"   | 5          | 5            | [[1, 3]]
    "i̇̀==>def<==b"                       | "ÌD==>EFB<=="              | "lt"   | 7          | 6            | [[3, 3]]
    "i̇̀==>def<==b"                       | "İ̀==>DEF<==B"             | "en"   | 7          | 7            | [[3, 3]]
    "i̇̀==>def<==b==>def<=="              | "İ̀==>DEF<==B==>DEF<=="    | "en"   | 10         | 10           | [[3, 3], [7, 3]]
    "\u00cc==>def<==b"                    | "\u00cc==>DEF<==B"          | "lt"   | 5          | 5            | [[1, 3]]
    "i̇̀i̇̀==>fff<==f123b"                | "ÌÌFF==>FF1<==23B"        | "lt"   | 14         | 12           | [[6, 3]]
    "i̇̀i̇̀i̇̀i̇̀EEEE==>fff<=="           | "ÌÌÌÌEEEEFFF"           | "lt"   | 19         | 15           | []
    "i̇̀i̇̀i̇̀i̇̀EEEE==>fff<==H==>GGG<==" | "ÌÌÌÌEEEEFFFH==>GGG<==" | "lt"   | 23         | 19           | [[16, 3]]
    "i̇̀i̇̀i̇̀EEEE==>fffgggg<=="          | "ÌÌÌEEEEFFF==>GGGG<=="   | "lt"   | 20         | 17           | [[13, 4]]
  }


  void 'test toLowerCase corner and pathologic cases'() {
    given:
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

  void 'onStringConstructor (#input)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final self = addFromTaintFormat(taintedObjects, input)
    final result = new String(self)

    when:
    module.onStringConstructor(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    input            | expected
    "==>123<=="      | "==>123<=="
    sb("==>123<==")  | "==>123<=="
    sbf("==>123<==") | "==>123<=="
  }

  void 'onStringConstructor empty (#input)'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final self = addFromTaintFormat(taintedObjects, input)
    final result = new String(self)

    when:
    module.onStringConstructor(self, result)

    then:
    null == taintedObjects.get(result)
    result == expected

    where:
    input   | expected
    ""      | ""
    sb("")  | ""
    sbf("") | ""
  }

  void 'test trim and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.trim()

    when:
    module.onStringTrim(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString     | expected
    "==>123<==   " | "==>123<=="
  }

  void 'test trim for not empty string cases'() {
    given:
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

  void 'onStringFormat fmt: #formatTainted args: #argsTainted'() {
    given:
    final to = ctx.getTaintedObjects()
    final format = addFromTaintFormat(to, formatTainted)
    final args = argsTainted.collect {
      final value = taint(to, it)
      objectHolder.add(value)
      return value
    }
    final formatted = String.format(format, args as Object[])
    final expected = getStringFromTaintFormat(expectedTainted)
    assert expected == formatted // validate expectation is OK

    when:
    module.onStringFormat(format, args as Object[], formatted)

    then:
    final tainted = to.get(formatted)
    final formattedResult = taintFormat(formatted, tainted?.ranges)
    assert formattedResult == expectedTainted: tainted?.ranges

    where:
    formatTainted                   | argsTainted                         | expectedTainted
    'Hello World!'                  | []                                  | 'Hello World!'
    '%s %s'                         | ['Hello', 'World!']                 | 'Hello World!'
    '%s %s'                         | ['He==>ll<==o', 'World==>!<==']     | 'He==>ll<==o World==>!<=='
    'He==>ll<==o %s'                | ['World==>!<==']                    | 'He==>ll<==o World==>!<=='
    'Hello %.6s'                    | ['Wor==>ld!!!<==']                  | 'Hello ==>World!<==' // limiting width
    'Hello %10s'                    | ['Wor==>ld!<==']                    | 'Hello ==>    World!<==' // padding left
    'Hello %-10s'                   | ['Wor==>ld!<==']                    | 'Hello ==>World!    <==' // padding right
    '%2$s %1$s'                     | ['World==>!<==', 'He==>ll<==o']     | 'He==>ll<==o World==>!<==' // indexed arguments
    '%s %3$s%s'                     | ['He==>ll<==o', 'ld==>!<==', 'Wor'] | 'He==>ll<==o World==>!<==' // mixed indexed arguments
    'I have %+.4f$'                 | [23.5D]                             | 'I have ==>+23.5000<==$' // numeric
    'The date is %1$td/%1$tm/%1$ty' | [date('yyyy.MM.dd', '2012.11.23')]  | 'The date is ==>23<==/==>11<==/==>12<==' // date
    'The time is %tT'               | [date('HH:mm ss', '12:00 00')]      | 'The time is ==>12:00:00<==' // time
    'Tainted not used %s'           | ['He==>ll<==o', 'World==>!<==']     | 'Tainted not used He==>ll<==o' // extra args
    'Hello ==>%s<=='                | ['World!']                          | 'Hello ==>World!<==' // tainted placeholder [non tainted parameter]
    'He==>llo %s!<=='               | ['World']                           | 'He==>llo <====>World<====>!<==' // tainted placeholder (2) [non tainted parameter]
    'He==>llo %s!<=='               | ['W==>or<==ld']                     | 'He==>llo <==W==>or<==ld==>!<==' // tainted placeholder (3) [mixing with tainted parameter]
    'Hello %n %n %s!%n'             | ['W==>or<==ld']                     | 'Hello \n \n W==>or<==ld!\n' // \n character
    'Hello %% %% %s!%%'             | ['W==>or<==ld']                     | 'Hello % % W==>or<==ld!%' // % character
    '==>Hello %n %s!<=='            | ['World']                           | '==>Hello <====>\n<====> <====>World<====>!<==' // \n character in tainted format (each placeholder generates a separate range)
    '==>Hello %% %s!<=='            | ['World']                           | '==>Hello <====>%<====> <====>World<====>!<==' // % character in tainted format (each placeholder generates a separate range)
  }

  void 'onStringFormat literals: #literals args: #argsTainted'() {
    given:
    final to = ctx.getTaintedObjects()
    final args = argsTainted.collect {
      final value = taint(to, it)
      objectHolder.add(value)
      return value
    }
    final expected = getStringFromTaintFormat(expectedTainted)

    when:
    module.onStringFormat(literals, args as Object[], expected)

    then:
    final tainted = to.get(expected)
    final formattedResult = taintFormat(expected, tainted?.ranges)
    assert formattedResult == expectedTainted: tainted?.ranges

    where:
    literals          | argsTainted                        | expectedTainted
    ['Hello World!']  | []                                 | 'Hello World!'
    ['', ' ', '']     | ['Hello', 'World!']                | 'Hello World!'
    ['', ' ', '']     | ['He==>ll<==o', 'World==>!<==']    | 'He==>ll<==o World==>!<=='
    ['Hello World!']  | []                                 | 'Hello World!'
    ['Today is ', ''] | [date('yyyy.MM.dd', '2012.11.23')] | "Today is ==>${String.valueOf(date('yyyy.MM.dd', '2012.11.23'))}<=="
    ['', '']          | ['He==>ll<==o', 'World==>!<==']    | 'He==>ll<==o' // extra args
  }

  void 'onSplit'() {
    given:
    final to = ctx.getTaintedObjects()
    def self = addFromTaintFormat(to, testString)
    def result = self.split(regexp)
    assert expectedTaintedArray.length == result.length

    def expectedArray = new String[expectedTaintedArray.length]
    for (int i = 0; i < expectedTaintedArray.length; i++) {
      expectedArray[i] = getStringFromTaintFormat(expectedTaintedArray[i])
      assert expectedArray[i] == result[i]
    }

    when:
    module.onSplit(self, result)

    then:
    for (int i = 0; i < expectedTaintedArray.length; i++) {
      final tainted = to.get(result[i])
      final formattedResult = taintFormat(result[i], tainted?.ranges)
      assert formattedResult == expectedTaintedArray[i]: tainted?.ranges
    }

    where:
    testString                     | regexp | expectedTaintedArray
    'test'                         | ' '    | ['test'] as String[]
    '==>test<=='                   | ' '    | ['==>test<=='] as String[]
    't==>es<==t'                   | ' '    | ['t==>es<==t'] as String[]
    'testing the test'             | ' '    | ['testing', 'the', 'test'] as String[]
    '==>testing the test<=='       | ' '    | ['==>testing<==', '==>the<==', '==>test<=='] as String[]
    '==>testing<== the test'       | ' '    | ['==>testing<==', '==>the<==', '==>test<=='] as String[]
    'testing ==>the test<=='       | ' '    | ['==>testing<==', '==>the<==', '==>test<=='] as String[]
    '==>testing<== the ==>test<==' | ' '    | ['==>testing<==', '==>the<==', '==>test<=='] as String[]
  }

  void 'test strip and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    objectHolder.add(self)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    module.onStringStrip(self, result, trailing)

    then:
    1 * tracer.activeSpan() >> span
    def to = ctx.getTaintedObjects().get(result)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    trailing | testString                                                      | expected
    false    | "   ==>123<==   "                                               | "==>123<=="
    false    | "   ==>123<==   "                                               | "==>123<==   "
    true     | "   ==>123<==   "                                               | "   ==>123<=="
    false    | " ==>   <== ==>   <== ==>456<== ==>ABC<== ==>   <== ==>   <== " | "==>456<== ==>ABC<=="
    false    | " ==>   <== ==>   <== ==>456<== ==>ABC<== ==>   <== ==>   <== " | "==>456<== ==>ABC<== ==>   <== ==>   <== "
    true     | " ==>   <== ==>   <== ==>456<== ==>ABC<== ==>   <== ==>   <== " | " ==>   <== ==>   <== ==>456<== ==>ABC<=="
    false    | "   ==>123<==   "                                               | "==>123<=="
    false    | "   ==>123<==   "                                               | "==>123<==   "
    true     | "   ==>123<==   "                                               | "   ==>123<=="
    false    | "==>   123   <=="                                               | "==>123<=="
    false    | "==>   123   <=="                                               | "==>123   <=="
    true     | "==>   123   <=="                                               | "==>   123<=="
    false    | "   a==> b <==c   "                                             | "a==> b <==c"
    false    | "   a==> b <==c   "                                             | "a==> b <==c   "
    true     | "   a==> b <==c   "                                             | "   a==> b <==c"
  }

  void 'test strip for empty string cases'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    objectHolder.add(self)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)

    when:
    module.onStringStrip(self, result, trailing)

    then:
    0 * tracer.activeSpan() >> span
    null == taintedObjects.get(result)
    result == expected

    where:
    trailing | testString    | expected
    false    | " ==>   <== " | ""
    false    | " ==>   <== " | ""
    true     | " ==>   <== " | ""
    false    | ""            | ""
    false    | ""            | ""
    true     | ""            | ""
  }

  void 'test indent and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    objectHolder.add(self)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    module.onIndent(self, indentation, result)

    then:
    1 * tracer.activeSpan() >> span
    def to = ctx.getTaintedObjects().get(result)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }
    where:
    indentation | testString                       | expected
    4           | "==>123<==\n12==>3<=="           | "    ==>123<==\n    12==>3<=="
    4           | "==>123<==\r\n12==>3<=="         | "    ==>123<==\n    12==>3<=="
    4           | "==>123\n1<==2==>3<=="           | "    ==>123\n    1<==2==>3<=="
    4           | "==>123\r\n1<==2==>3<=="         | "    ==>123\n    1<==2==>3<=="
    0           | "==>123<==\r\n==>123<=="         | "==>123<==\n==>123<=="
    0           | "==>123\r\n<====>123<=="         | "==>123\n<====>123<=="
    0           | "==>123<==\r==>123<=="           | "==>123<==\n==>123<=="
    0           | "==>123\r<====>123<=="           | "==>123\n<====>123<=="
    -4          | "    ==>123<==\n    12==>3<=="   | "==>123<==\n12==>3<=="
    -4          | "    ==>123<==\r\n    12==>3<==" | "==>123<==\n12==>3<=="
    -4          | "    ==>123\n    1<==2==>3<=="   | "==>123\n1<==2==>3<=="
    -4          | "    ==>123\r\n    1<==2==>3<==" | "==>123\n1<==2==>3<=="
  }

  void 'test replace with a single char and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.replace(oldChar, newChar)

    when:
    module.onStringReplace(self, oldChar as char, newChar as char, result)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString       | oldChar | newChar | expected
    "==>masquita<==" | 'a'     | 'o'     | "==>mosquito<=="
    "==>___<=="      | '_'     | '-'     | "==>---<=="
    "==>my_input<==" | '_'     | '-'     | "==>my-input<=="
  }

  void 'test replace with a char sequence (not tainted) and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def originalReplace = self.replace(oldCharSeq, newCharSeq)

    when:
    def result = module.onStringReplace(self, oldCharSeq, newCharSeq)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    originalReplace == result
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString                                   | oldCharSeq | newCharSeq | expected
    "==>masquita<=="                             | 'as'       | 'os'       | "==>m<==os==>quita<=="
    "==>masquita<=="                             | 'os'       | 'as'       | "==>masquita<=="
    "==>m<==as==>qu<==i==>ta<=="                 | 'as'       | 'os'       | "==>m<==os==>qu<==i==>ta<=="
    "==>my_input<=="                             | 'in'       | 'out'      | "==>my_<==out==>put<=="
    "==>my_output<=="                            | 'out'      | 'in'       | "==>my_<==in==>put<=="
    "==>my_input<=="                             | '_'        | '-'        | "==>my<==-==>input<=="
    "==>my<==_==>input<=="                       | 'in'       | 'out'      | "==>my<==_out==>put<=="
    "==>my_in<==p==>ut<=="                       | 'in'       | 'out'      | "==>my_<==outp==>ut<=="
    "==>my_<==in==>put<=="                       | 'in'       | 'out'      | "==>my_<==out==>put<=="
    "==>my_i<==n==>put<=="                       | 'in'       | 'out'      | "==>my_<==out==>put<=="
    "==>my_<==i==>nput<=="                       | 'in'       | 'out'      | "==>my_<==out==>put<=="
    "==>my_o<==u==>tput<=="                      | 'out'      | 'in'       | "==>my_<==in==>put<=="
    "==>my_o<==u==>tput<====>my_o<==u==>tput<==" | 'out'      | 'in'       | "==>my_<==in==>put<====>my_<==in==>put<=="
    "==>my_o<==u==>tp<==ut"                      | 'output'   | 'input'    | "==>my_<==input"
    "==>my_input<=="                             | '_'        | '/\\,.*+'  | "==>my<==/\\,.*+==>input<=="
    "==>my_input<=="                             | '_'        | '!?^&$#'   | "==>my<==!?^&\$#==>input<=="
    "==>my_input<=="                             | '_'        | ')(][}{'   | "==>my<==)(][}{==>input<=="
  }

  void 'test replace with a char sequence (tainted) and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def inputTainted = addFromTaintFormat(taintedObjects, newCharSeq)
    def originalReplace = self.replace(oldCharSeq, inputTainted)

    when:
    def result = module.onStringReplace(self, oldCharSeq, inputTainted)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    originalReplace == result
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString                                   | oldCharSeq | newCharSeq      | expected
    "==>masquita<=="                             | 'as'       | '==>os<=='      | "==>m<====>os<====>quita<=="
    "==>masquita<=="                             | 'os'       | '==>as<=='      | "==>masquita<=="
    "masquita"                                   | 'as'       | '==>os<=='      | "m==>os<==quita"
    "==>m<==as==>qu<==i==>ta<=="                 | 'as'       | '==>os<=='      | "==>m<====>os<====>qu<==i==>ta<=="
    "==>my_input<=="                             | 'in'       | '==>out<=='     | "==>my_<====>out<====>put<=="
    "==>my_output<=="                            | 'out'      | '==>in<=='      | "==>my_<====>in<====>put<=="
    "==>my_input<=="                             | '_'        | '==>-<=='       | "==>my<====>-<====>input<=="
    "==>my<==_==>input<=="                       | 'in'       | '==>out<=='     | "==>my<==_==>out<====>put<=="
    "==>my_in<==p==>ut<=="                       | 'in'       | '==>out<=='     | "==>my_<====>out<==p==>ut<=="
    "==>my_<==in==>put<=="                       | 'in'       | '==>out<=='     | "==>my_<====>out<====>put<=="
    "==>my_i<==n==>put<=="                       | 'in'       | '==>out<=='     | "==>my_<====>out<====>put<=="
    "==>my_<==i==>nput<=="                       | 'in'       | '==>out<=='     | "==>my_<====>out<====>put<=="
    "==>my_o<==u==>tput<=="                      | 'out'      | '==>in<=='      | "==>my_<====>in<====>put<=="
    "==>my_o<==u==>tput<====>my_o<==u==>tput<==" | 'out'      | '==>in<=='      | "==>my_<====>in<====>put<====>my_<====>in<====>put<=="
    "==>my_o<==u==>tp<==ut"                      | 'output'   | '==>input<=='   | "==>my_<====>input<=="
    "==>my_input<=="                             | '_'        | '==>/\\,.*+<==' | "==>my<====>/\\,.*+<====>input<=="
    "==>my_input<=="                             | '_'        | '==>!?^&$#<=='  | "==>my<====>!?^&\$#<====>input<=="
    "==>my_input<=="                             | '_'        | '==>)(][}{<=='  | "==>my<====>)(][}{<====>input<=="
  }

  void 'test replace with a regex and replacement (not tainted) and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def originalReplace
    if (numReplacements > 1) {
      originalReplace = self.replaceAll(regex, replacement)
    } else {
      originalReplace = self.replaceFirst(regex, replacement)
    }

    when:
    def result = module.onStringReplace(self, regex, replacement, numReplacements)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    if (numReplacements > 0) {
      originalReplace == result
    }
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString                                   | regex    | replacement | numReplacements   | expected
    "==>masquita<=="                             | 'as'     | 'os'        | Integer.MAX_VALUE | "==>m<==os==>quita<=="
    "==>masquita<=="                             | 'os'     | 'as'        | Integer.MAX_VALUE | "==>masquita<=="
    "==>m<==as==>qu<==i==>ta<=="                 | 'as'     | 'os'        | Integer.MAX_VALUE | "==>m<==os==>qu<==i==>ta<=="
    "==>my_input<=="                             | 'in'     | 'out'       | Integer.MAX_VALUE | "==>my_<==out==>put<=="
    "==>my_output<=="                            | 'out'    | 'in'        | Integer.MAX_VALUE | "==>my_<==in==>put<=="
    "==>my_input<=="                             | '_'      | '-'         | Integer.MAX_VALUE | "==>my<==-==>input<=="
    "==>my<==_==>input<=="                       | 'in'     | 'out'       | Integer.MAX_VALUE | "==>my<==_out==>put<=="
    "==>my_in<==p==>ut<=="                       | 'in'     | 'out'       | Integer.MAX_VALUE | "==>my_<==outp==>ut<=="
    "==>my_<==in==>put<=="                       | 'in'     | 'out'       | Integer.MAX_VALUE | "==>my_<==out==>put<=="
    "==>my_i<==n==>put<=="                       | 'in'     | 'out'       | Integer.MAX_VALUE | "==>my_<==out==>put<=="
    "==>my_<==i==>nput<=="                       | 'in'     | 'out'       | Integer.MAX_VALUE | "==>my_<==out==>put<=="
    "==>my_o<==u==>tput<=="                      | 'out'    | 'in'        | Integer.MAX_VALUE | "==>my_<==in==>put<=="
    "==>my_o<==u==>tput<====>my_o<==u==>tput<==" | 'out'    | 'in'        | Integer.MAX_VALUE | "==>my_<==in==>put<====>my_<==in==>put<=="
    "==>my_o<==u==>tp<==ut"                      | 'output' | 'input'     | Integer.MAX_VALUE | "==>my_<==input"
    "==>my_input<=="                             | '_'      | '/\\,.*+'   | Integer.MAX_VALUE | "==>my<==/\\,.*+==>input<=="
    "==>my_input<=="                             | '_'      | '!?^&#'     | Integer.MAX_VALUE | "==>my<==!?^&#==>input<=="
    "==>my_input<=="                             | '_'      | ')(][}{'    | Integer.MAX_VALUE | "==>my<==)(][}{==>input<=="
  }

  void 'test replace with a regex and replacement (tainted) and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def inputTainted = addFromTaintFormat(taintedObjects, replacement)
    def originalReplace
    if (numReplacements > 1) {
      originalReplace = self.replaceAll(regex, inputTainted)
    } else {
      originalReplace = self.replaceFirst(regex, inputTainted)
    }

    when:
    def result = module.onStringReplace(self, regex, inputTainted, numReplacements)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    if (numReplacements > 0) {
      originalReplace == result
    }
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString                                   | regex    | replacement     | numReplacements   | expected
    "==>masquita<=="                             | 'as'     | '==>os<=='      | Integer.MAX_VALUE | "==>m<====>os<====>quita<=="
    "==>masquita<=="                             | 'os'     | '==>as<=='      | Integer.MAX_VALUE | "==>masquita<=="
    "masquita"                                   | 'as'     | '==>os<=='      | Integer.MAX_VALUE | "m==>os<==quita"
    "==>m<==as==>qu<==i==>ta<=="                 | 'as'     | '==>os<=='      | Integer.MAX_VALUE | "==>m<====>os<====>qu<==i==>ta<=="
    "==>my_input<=="                             | 'in'     | '==>out<=='     | Integer.MAX_VALUE | "==>my_<====>out<====>put<=="
    "==>my_output<=="                            | 'out'    | '==>in<=='      | Integer.MAX_VALUE | "==>my_<====>in<====>put<=="
    "==>my_input<=="                             | '_'      | '==>-<=='       | Integer.MAX_VALUE | "==>my<====>-<====>input<=="
    "==>my<==_==>input<=="                       | 'in'     | '==>out<=='     | Integer.MAX_VALUE | "==>my<==_==>out<====>put<=="
    "==>my_in<==p==>ut<=="                       | 'in'     | '==>out<=='     | Integer.MAX_VALUE | "==>my_<====>out<==p==>ut<=="
    "==>my_<==in==>put<=="                       | 'in'     | '==>out<=='     | Integer.MAX_VALUE | "==>my_<====>out<====>put<=="
    "==>my_i<==n==>put<=="                       | 'in'     | '==>out<=='     | Integer.MAX_VALUE | "==>my_<====>out<====>put<=="
    "==>my_<==i==>nput<=="                       | 'in'     | '==>out<=='     | Integer.MAX_VALUE | "==>my_<====>out<====>put<=="
    "==>my_o<==u==>tput<=="                      | 'out'    | '==>in<=='      | Integer.MAX_VALUE | "==>my_<====>in<====>put<=="
    "==>my_o<==u==>tput<====>my_o<==u==>tput<==" | 'out'    | '==>in<=='      | Integer.MAX_VALUE | "==>my_<====>in<====>put<====>my_<====>in<====>put<=="
    "==>my_o<==u==>tp<==ut"                      | 'output' | '==>input<=='   | Integer.MAX_VALUE | "==>my_<====>input<=="
    "==>my_o<==u==>tput<====>my_o<==u==>tput<==" | 'out'    | '==>in<=='      | 1                 | "==>my_<====>in<====>put<====>my_o<==u==>tput<=="
    "==>my_o<==u==>tput<====>my_o<==u==>tput<==" | 'out'    | '==>in<=='      | 0                 | "==>my_o<==u==>tput<====>my_o<==u==>tput<=="
    "==>my_input<=="                             | '_'      | '==>/\\,.*+<==' | Integer.MAX_VALUE | "==>my<====>/\\,.*+<====>input<=="
    "==>my_input<=="                             | '_'      | '==>!?^&#<=='   | Integer.MAX_VALUE | "==>my<====>!?^&#<====>input<=="
    "==>my_input<=="                             | '_'      | '==>)(][}{<=='  | Integer.MAX_VALUE | "==>my<====>)(][}{<====>input<=="
  }

  void 'test valueOf with (#param) and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def paramTainted = addFromTaintFormat(taintedObjects, param)
    def result = String.valueOf(paramTainted)

    when:
    module.onStringValueOf(paramTainted, result)
    def taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    param                 | expected
    "==>test<=="          | "==>test<=="
    sb("==>test<==")      | "==>test<=="
    sbf("==>my_input<==") | "==>my_input<=="
  }

  void 'test valueOf with taintable object and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final source = taintedSource()
    final param = taintable(taintedObjects, source)
    final result = String.valueOf(param)

    when:
    module.onStringValueOf(param, result)
    final taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    taintFormat(result, taintedObject.getRanges()) == "==>my_input<=="
  }

  @IgnoreIf({ System.getProperty('java.specification.version').toBigDecimal() < 15 })
  void 'test translate escapes'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, testString)
    def result = self.translateEscapes()

    when:
    module.onStringTranslateEscapes(self, result)
    def taintedObject = taintedObjects.get(result)

    then:
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    testString            | expected
    "==>hello world\t<==" | "==>hello world\t<=="
    "==>hello world\n<==" | "==>hello world\n<=="
    "==>hello worldn<=="  | "==>hello worldn<=="
  }

  void 'test valueOf with special objects and make sure IastRequestContext is called'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final source = taintedSource()
    final param = new Object() {
        @Override
        String toString() {
          return "my_input"
        }
      }
    taintObject(taintedObjects, param, source)
    final result = String.valueOf(param)

    when:
    module.onStringValueOf(param, result)
    final taintedObject = taintedObjects.get(result)

    then:
    1 * tracer.activeSpan() >> span
    taintFormat(result, taintedObject.getRanges()) == "==>my_input<=="
  }

  void 'onStringBuilderSetLength is empty or different lengths (#self, #length)'() {
    given:
    self?.setLength(self.length())

    when:
    module.onStringBuilderSetLength(self, length)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    self       | length | mockCalls
    sb("123")  | 2      | 0
    sb()       | 0      | 1
    sbf("123") | 2      | 0
    sbf()      | 0      | 1
  }

  void 'onStringBuilderSetLength (#input, #length)'() {
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, input)
    if (self instanceof StringBuilder) {
      ((StringBuilder) self).setLength(length)
    } else if (self instanceof StringBuffer) {
      ((StringBuffer) self).setLength(length)
    }
    final result = self.toString()

    when:
    module.onStringBuilderSetLength(self, length)
    def taintedObject = taintedObjects.get(self)

    then:
    1 * tracer.activeSpan() >> span
    taintFormat(result, taintedObject.getRanges()) == expected

    where:
    input                          | length | expected
    sb("==>0123<==")               | 3      | "==>012<=="
    sb("0123==>456<==78")          | 5      | "0123==>4<=="
    sb("01==>234<==5==>678<==90")  | 8      | "01==>234<==5==>67<=="
    sbf("==>0123<==")              | 3      | "==>012<=="
    sbf("0123==>456<==78")         | 5      | "0123==>4<=="
    sbf("01==>234<==5==>678<==90") | 8      | "01==>234<==5==>67<=="
  }

  void 'onStringBuilderSetLength untainting after setLength (#input, #length)'() {
    final taintedObjects = ctx.getTaintedObjects()
    def self = addFromTaintFormat(taintedObjects, input)
    if (self instanceof StringBuilder) {
      ((StringBuilder) self).setLength(length)
    } else if (self instanceof StringBuffer) {
      ((StringBuffer) self).setLength(length)
    }

    when:
    module.onStringBuilderSetLength(self, length)
    def taintedObject = taintedObjects.get(self)

    then:
    1 * tracer.activeSpan() >> span
    taintedObject == null

    where:
    input                  | length
    sb("==>0123<==")       | 0
    sb("0123==>456<==78")  | 3
    sbf("==>0123<==")      | 0
    sbf("0123==>456<==78") | 3
  }

  private static Date date(final String pattern, final String value) {
    return new SimpleDateFormat(pattern).parse(value)
  }

  private static StringBuilder sb() {
    return sb('')
  }

  private static StringBuilder sb(final String string) {
    return new StringBuilder(string)
  }

  private static StringBuffer sbf() {
    return sbf('')
  }

  private static StringBuffer sbf(final String string) {
    return new StringBuffer(string)
  }

  private static Source taintedSource(String value = 'value') {
    return new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', value)
  }

  private static Taintable taintable(TaintedObjects tos, Source source = null) {
    final result = new MockTaintable()
    if (source != null) {
      taintObject(tos, result, source)
    }
    return result
  }

  private static class MockTaintable implements Taintable {
    private Source source

    @SuppressWarnings('CodeNarc')
    @Override
    Source $$DD$getSource() {
      return source
    }

    @SuppressWarnings('CodeNarc')
    @Override
    void $$DD$setSource(Source source) {
      this.source = source
    }

    @Override
    String toString() {
      return "my_input"
    }
  }

  void 'test string format with incompatible type for float specifier'() {
    given:
    final pattern = 'User: %s and Balance: %f'
    final params = ['admin', 'not-a-number'] as Object[]

    when:
    // This should not throw IllegalFormatConversionException
    // The fix should handle it gracefully with String.valueOf() fallback
    final result = String.format(pattern, params)

    then:
    // Before the fix, this would throw IllegalFormatConversionException
    // After the fix, it should work via formatValue() helper
    thrown(IllegalFormatConversionException)
  }

  void 'test onStringFormat with incompatible type for float specifier'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final pattern = 'User: %s and Balance: %f'
    final params = [
      addFromTaintFormat(taintedObjects, '==>admin<=='),
      addFromTaintFormat(taintedObjects, '==>not-a-number<==')
    ] as Object[]
    final result = 'User: admin and Balance: not-a-number'
    objectHolder.add(params[0])
    objectHolder.add(params[1])

    when:
    // This should not throw IllegalFormatConversionException thanks to the fix
    // Result will have fallback formatting: "User: admin and Balance: not-a-number"
    module.onStringFormat(pattern, params, result)

    then:
    // Should complete without throwing IllegalFormatConversionException
    notThrown(IllegalFormatConversionException)

    // Verify the result is tainted
    final tainted = taintedObjects.get(result)
    tainted != null
    tainted.getRanges().length > 0
  }

  void 'test onStringFormat with multiple incompatible types'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    final pattern = 'Name: %s, Age: %d, Score: %f'
    final params = [
      addFromTaintFormat(taintedObjects, '==>John<=='),
      addFromTaintFormat(taintedObjects, '==>thirty<=='),
      addFromTaintFormat(taintedObjects, '==>high<==')
    ] as Object[]
    final result = 'Name: John, Age: thirty, Score: high'
    objectHolder.add(params[0])
    objectHolder.add(params[1])
    objectHolder.add(params[2])

    when:
    // This should not throw IllegalFormatConversionException thanks to the fix
    module.onStringFormat(pattern, params, result)

    then:
    // Should complete without throwing IllegalFormatConversionException
    notThrown(IllegalFormatConversionException)

    // Verify the result is tainted
    final tainted = taintedObjects.get(result)
    tainted != null
    tainted.getRanges().length > 0
  }
}
