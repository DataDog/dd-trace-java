package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.propagation.StringModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class StringModuleTest extends IastModuleImplTestBase {

  private StringModule module

  private List<Object> objectHolder

  def setup() {
    module = registerDependencies(new StringModuleImpl())
    objectHolder = []
  }

  void 'onStringBuilderAppend null or empty (#builder, #param)'(final StringBuilder builder, final String param) {
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

  void 'onStringBuilderAppend without span (#builder, #param)'(final StringBuilder builder, final String param, final int mockCalls) {
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

  void 'onStringBuilderAppend (#builder, #param)'(StringBuilder builder, String param, final int mockCalls, final String expected) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
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

  void 'onStringBuilderInit null or empty (#builder, #param)'(final StringBuilder builder, final String param) {
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

  void 'onStringBuilderInit without span (#builder, #param)'(final StringBuilder builder, final String param, final int mockCalls) {
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

  void 'onStringBuilderInit (#builder, #param)'(StringBuilder builder, String param, final int mockCalls, final String expected) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
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
    final builder = sb('1')
    final result = builder.toString()

    when:
    module.onStringBuilderToString(builder, result)

    then:
    1 * tracer.activeSpan() >> null
    0 * _
  }

  void 'onStringBuilderToString (#builder)'(StringBuilder builder, final String expected) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
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

  void 'onStringConcatFactory null or empty (#args)'(final List<String> args,
    final String recipe,
    final List<Object> constants,
    final List<Integer> recipeOffsets) {
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

  void 'onStringConcatFactory without span (#args)'(final List<String> args,
    final String recipe,
    final List<Object> constants,
    final List<Integer> recipeOffsets) {
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

  void 'onStringConcatFactory (#args, #recipe, #constants)'(List<String> args,
    final String recipe,
    final List<Object> constants,
    final List<Integer> recipeOffsets,
    final String expected) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
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

  void 'onStringConcat without span (#left, #right)'(final String left, final String right) {
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

  void 'onStringConcat (#left, #right)'(String left, String right, final String expected) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
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

  void 'onStringSubSequence null ,empty or string not change after subsequence (#self, #beginIndex, #endIndex)'(final String self, final int beginIndex, final int endIndex) {
    given:
    final result = self?.substring(beginIndex, endIndex)
    when:
    module.onStringSubSequence(self, beginIndex, endIndex, result)
    then:
    0 * _
    where:
    self         | beginIndex | endIndex
    ""           | 0          | 0
    null         | 0          | 0
    "not_change" | 0          | 10
  }

  void 'onStringSubSequence without span (#self, #beginIndex, #endIndex)'(final String self, final int beginIndex, final int endIndex, final int mockCalls) {
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
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    and:
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
    self                      | beginIndex | endIndex | expected
    "0123==>456<==78"         | 1          | 8        | "123==>456<==7"
    "0123==>456<==78"         | 0          | 4        | "0123"
    "0123==>456<==78"         | 7          | 9        | "78"
    "0123==>456<==78"         | 1          | 5        | "123==>4<=="
    "0123==>456<==78"         | 1          | 6        | "123==>45<=="
    "0123==>456<==78"         | 4          | 7        | "==>456<=="
    "0123==>456<==78"         | 6          | 8        | "==>6<==7"
    "0123==>456<==78"         | 5          | 8        | "==>56<==7"
    "0123==>456<==78"         | 4          | 6        | "==>45<=="
    "01==>234<==5==>678<==90" | 1          | 10       | "1==>234<==5==>678<==9"
    "01==>234<==5==>678<==90" | 1          | 2        | "1"
    "01==>234<==5==>678<==90" | 5          | 6        | "5"
    "01==>234<==5==>678<==90" | 9          | 10       | "9"
    "01==>234<==5==>678<==90" | 1          | 4        | "1==>23<=="
    "01==>234<==5==>678<==90" | 2          | 4        | "==>23<=="
    "01==>234<==5==>678<==90" | 2          | 5        | "==>234<=="
    "01==>234<==5==>678<==90" | 1          | 8        | "1==>234<==5==>67<=="
    "01==>234<==5==>678<==90" | 2          | 8        | "==>234<==5==>67<=="
    "01==>234<==5==>678<==90" | 2          | 9        | "==>234<==5==>678<=="
    "01==>234<==5==>678<==90" | 5          | 8        | "5==>67<=="
    "01==>234<==5==>678<==90" | 6          | 8        | "==>67<=="
    "01==>234<==5==>678<==90" | 6          | 9        | "==>678<=="
    "01==>234<==5==>678<==90" | 4          | 9        | "==>4<==5==>678<=="
    "01==>234<==5==>678<==90" | 4          | 8        | "==>4<==5==>67<=="
  }

  private static StringBuilder sb() {
    return sb('')
  }

  private static StringBuilder sb(final String string) {
    return new StringBuilder(string)
  }
}
