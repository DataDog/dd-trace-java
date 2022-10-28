package com.datadog.iast


import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplOnStringConcatFactoryTest extends IastModuleImplTestBase {

  @Shared
  private List<Object> objectHolder = []

  def setup() {
    objectHolder.clear()
  }

  void 'onStringConcatFactory null or empty (#args)'(final List<String> args,
    final String recipe,
    final List<Object> constants,
    final List<Integer> recipeOffsets) {
    given:
    final result = args.inject('') { res, item -> res + item }

    when:
    module.onStringConcatFactory(args as String[], result, recipe, constants as Object[], recipeOffsets as int[])

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
    module.onStringConcatFactory(args as String[], result, recipe, constants as Object[], recipeOffsets as int[])

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
    module.onStringConcatFactory(args as String[], result, recipe, constants as Object[], recipeOffsets as int[])

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
}


