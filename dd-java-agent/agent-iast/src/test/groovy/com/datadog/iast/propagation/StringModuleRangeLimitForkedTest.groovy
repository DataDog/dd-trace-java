package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import datadog.trace.api.config.IastConfig
import datadog.trace.api.iast.propagation.StringModule

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taint
import static com.datadog.iast.taint.TaintUtils.taintFormat

class StringModuleRangeLimitForkedTest extends IastModuleImplTestBase {

  private StringModule module

  def setup() {
    injectSysConfig(IastConfig.IAST_MAX_RANGE_COUNT, '2')

    module = new StringModuleImpl()
  }

  void 'onStringConcatFactory'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    args = args.collect { it ->
      final item = addFromTaintFormat(taintedObjects, it)
      objectHolder.add(item)
      return item
    }
    final recipe = args.collect { '\u0001' }.join()
    final recipeOffsets = (0..<recipe.length()).collect { it }

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)

    when:
    module.onStringConcatFactory(result, args as String[], recipe, [] as Object[], recipeOffsets as int[])

    then:
    final to = ctx.getTaintedObjects().get(result)
    to != null
    to.get() == result
    taintFormat(to.get() as String, to.getRanges()) == expected

    where:
    args                              | expected
    ['==>1<==', '==>2<==']            | '==>1<====>2<=='
    ['==>1<==', '==>2<==', '==>3<=='] | '==>1<====>2<==3'
  }

  void 'onString'() {
    given:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)

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
    final to = ctx.getTaintedObjects().get(result)
    to != null
    to.get() == result
    taintFormat(to.get() as String, to.getRanges()) == expected

    where:
    delimiter | elements                          | expected
    ""        | ['==>1<==', '==>2<==']            | '==>1<====>2<=='
    ""        | ['==>1<==', '==>2<==', '==>3<=='] | '==>1<====>2<==3'
    "==>,<==" | ['1', '2', '3']                   | '1==>,<==2==>,<==3'
    "==>,<==" | ['1', '2', '3', '4']              | '1==>,<==2==>,<==3,4'
    "==>,<==" | ['==>1<==', '==>2<==', '==>3<=='] | '==>1<====>,<==23'
  }

  void 'onStringRepeat'() {
    given:
    final taintedObjects = ctx.getTaintedObjects()
    self = addFromTaintFormat(taintedObjects, self)
    objectHolder.add(self)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)

    when:
    module.onStringRepeat(self, count, result)

    then:
    final to = ctx.getTaintedObjects().get(result)
    to != null
    to.get() == result
    taintFormat(to.get() as String, to.getRanges()) == expected

    where:
    self        | count | expected
    "==>b<=="   | 2     | "==>b<====>b<=="
    "==>b<=="   | 3     | "==>b<====>b<==b"
    "aa==>b<==" | 2     | "aa==>b<==aa==>b<=="
    "aa==>b<==" | 3     | "aa==>b<==aa==>b<==aab"
    "==>b<==cc" | 2     | "==>b<==cc==>b<==ccbcc"
    "a==>b<==c" | 2     | "a==>b<==ca==>b<==c"
    "a==>b<==c" | 3     | "a==>b<==ca==>b<==cabc"
  }

  void 'onStringFormat'() {
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
    formatTainted        | argsTainted                       | expectedTainted
    '%s%s'               | ['==>1<==', '==>2<==']            | '==>1<====>2<=='
    '%s%s%s'             | ['==>1<==', '==>2<==', '==>3<=='] | '==>1<====>2<==3'
    '==>%s<====>%s<==%s' | ['1', '2', '==>3<==']             | '==>1<====>2<==3'
    '%s==>%s<====>%s<==' | ['1', '2', '==>3<==']             | '1==>2<====>3<=='
    '%s%s==>%s<=='       | ['==>1<==', '==>2<==', '3']       | '==>1<====>2<==3'
  }

  void 'onStringFormat literals'() {
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
    literals         | argsTainted                       | expectedTainted
    ['', '', '']     | ['==>1<==', '==>2<==']            | '==>1<====>2<=='
    ['', '', '', ''] | ['==>1<==', '==>2<==', '==>3<=='] | '==>1<====>2<==3'
  }
}
