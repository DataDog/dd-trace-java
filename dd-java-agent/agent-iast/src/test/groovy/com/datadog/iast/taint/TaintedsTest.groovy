package com.datadog.iast.taint


import datadog.trace.test.util.DDSpecification

class TaintedsTest extends DDSpecification {

  def 'can be tainted with char sequence #value ? #expected'(final CharSequence value, final boolean expected) {
    when:
    final result = Tainteds.canBeTainted((CharSequence)value)

    then:
    result == expected

    where:
    value                  | expected
    null                   | false
    ""                     | false
    "x"                    | true
    new StringBuilder()    | false
    new StringBuilder("")  | false
    new StringBuilder("x") | true
  }

  def 'can be tainted with array #value ? #expected'(final List<? extends CharSequence> value, final boolean expected) {
    when:
    final arary = value == null ? null : value.toArray(new CharSequence[0])
    final result = Tainteds.canBeTainted((CharSequence[]) arary)

    then:
    result == expected

    where:
    value           | expected
    []              | false
    [null]          | false
    [""]            | false
    ["a"]           | true
    ["a", null, ""] | true
  }

  def 'can be tainted with list #value ? #expected'(final List<? extends CharSequence> value, final boolean expected) {
    when:
    final result = Tainteds.canBeTainted(value)

    then:
    result == expected

    where:
    value           | expected
    []              | false
    [null]          | false
    [""]            | false
    ["a"]           | true
    ["a", null, ""] | true
  }

  def 'get tainted'(final Object value) {
    given:
    final to = Mock(TaintedObjects)

    when:
    final result = Tainteds.getTainted(to, value)

    then:
    if (value != null) {
      1 * to.get(value) >> null
    }
    result == null

    where:
    value           | _
    null            | _
    []              | _
    [null]          | _
    [""]            | _
    ["a"]           | _
    ["a", null, ""] | _
  }
}
