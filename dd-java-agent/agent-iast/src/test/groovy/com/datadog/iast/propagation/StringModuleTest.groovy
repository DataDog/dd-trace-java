package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.propagation.StringModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Assertions

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

@CompileDynamic
class StringModuleTest extends IastModuleImplTestBase {

  private StringModule module

  private List<Object> objectHolder

  def setup() {
    module = new StringModuleImpl()
    objectHolder = []
  }

  void 'onStringBuilderAppend null or empty (#builder, #param)'(StringBuilder builder, final String param) {
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

  void 'onStringBuilderAppend without span (#builder, #param)'(StringBuilder builder, final String param, final int mockCalls) {
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

  void 'onStringBuilderInit null or empty (#builder, #param)'(StringBuilder builder, final String param) {
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

  void 'onStringBuilderInit without span (#builder, #param)'(StringBuilder builder, final String param, final int mockCalls) {
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
    def builder = sb('1')
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

  void 'onStringSubSequence null ,empty or string not changed after subsequence (#self, #beginIndex, #endIndex)'(final String self, final int beginIndex, final int endIndex) {
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
    "==>0123<=="              | 0          | 4        | "==>0123<=="
    "0123==>456<==78"         | 0          | 5        | "0123==>4<=="
    "01==>234<==5==>678<==90" | 0          | 8        | "01==>234<==5==>67<=="
    "==>0123<=="              | 0          | 3        | "==>012<=="
    "==>0123<=="              | 1          | 4        | "==>123<=="
    "==>0123<=="              | 1          | 3        | "==>12<=="
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

  void 'onStringJoin without null delimiter or elements (#delimiter, #elements)'(final CharSequence delimiter, final CharSequence[] elements) {
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

  void 'onStringJoin (#delimiter, #elements)'(final CharSequence delimiter, final CharSequence[] elements, final String expected) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(expected) != null

    and:
    final taintedObjects = ctx.getTaintedObjects()
    final fromTaintedDelimiter = addFromTaintFormat(taintedObjects, delimiter)
    objectHolder.add(fromTaintedDelimiter)

    and:
    final fromTaintedElements = new CharSequence[elements.length]
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

  void 'onStringRepeat that can not be tainted (#self, #count)'(final String self, final int count, final String expected) {
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

  void 'onStringRepeat (#self, #count, #result)'() {
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
    module.onStringRepeat(self, count, result)

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
    "i̇̀==>def<==b"                         | "ÌD==>EFB<=="               | "lt"   | 7          | 6            | [[3, 3]]
    "i̇̀==>def<==b"                         | "İ̀==>DEF<==B"               | "en"   | 7          | 7            | [[3, 3]]
    "i̇̀==>def<==b==>def<=="                | "İ̀==>DEF<==B==>DEF<=="      | "en"   | 10         | 10           | [[3, 3], [7, 3]]
    "\u00cc==>def<==b"                    | "\u00cc==>DEF<==B"          | "lt"   | 5          | 5            | [[1, 3]]
    "i̇̀i̇̀==>fff<==f123b"                    | "ÌÌFF==>FF1<==23B"          | "lt"   | 14         | 12           | [[6, 3]]
    "i̇̀i̇̀i̇̀i̇̀EEEE==>fff<=="                   | "ÌÌÌÌEEEEFFF"               | "lt"   | 19         | 15           | []
    "i̇̀i̇̀i̇̀i̇̀EEEE==>fff<==H==>GGG<=="         | "ÌÌÌÌEEEEFFFH==>GGG<=="     | "lt"   | 23         | 19           | [[16, 3]]
    "i̇̀i̇̀i̇̀EEEE==>fffgggg<=="                | "ÌÌÌEEEEFFF==>GGGG<=="      | "lt"   | 20         | 17           | [[13, 4]]
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

    for (Integer i = 0; i < ranges.size(); i++) {
      Assertions.assertEquals(ranges[i].getStart(), expectedRanges[i][0])
      Assertions.assertEquals(ranges[i].getLength(), expectedRanges[i][1])
    }

    where:
    testString                   | expected               | locale | lengthSelf | lengthResult | expectedRanges
    "A==>123<==B"                | "a==>123<==b"          | "en"   | 5          | 5            | [[1, 3]]
    "\u00cc\u00cc==>123<==B"     | "ìì==>123<==b"         | "en"   | 6          | 6            | [[2, 3]]
    "\u00cc\u00cc==>123<==B"     | "i̇==>̀i̇<==̀123b"         | "lt"   | 6          | 10           | [[2, 3]]
    "\u00cc\u00ccFFFF==>123<==B" | "i̇̀i̇̀==>fff<==f123b"     | "lt"   | 10         | 14           | [[6, 3]]
    "A==>\u00cc\u00cc\u00cc<==B" | "a==>ììì<==b"          | "en"   | 5          | 5            | [[1, 3]]
  }

  void 'test trim and make sure IastRequestContext is called'() {
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

  private static StringBuilder sb() {
    return sb('')
  }

  private static StringBuilder sb(final String string) {
    return new StringBuilder(string)
  }
}
