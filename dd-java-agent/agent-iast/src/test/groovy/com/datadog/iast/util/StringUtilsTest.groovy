package com.datadog.iast.util

import spock.lang.Specification

class StringUtilsTest extends Specification {

  void 'test ends with ignore case'() {
    when:
    final endsWith = StringUtils.endsWithIgnoreCase(value, pattern)

    then:
    endsWith == expected

    where:
    value  | pattern | expected
    ''     | ''      | true
    'abc'  | ''      | true
    'abc'  | 'def'   | false
    'abc'  | '0abc'  | false
    '0abc' | 'abc'   | true
    'abc'  | 'aBc'   | true
    '0abc' | 'c'     | true
    'abc'  | 'C'     | true
  }

  void 'test substring with trim'() {
    when:
    final trimmed = StringUtils.substringTrim(value, start, end)

    then:
    trimmed == expected

    where:
    value          | start | end | expected
    ''             | 0     | 0   | ''
    ' '            | 0     | 1   | ''
    'abc'          | 1     | 3   | 'bc'
    '  abc'        | 1     | 3   | 'a'
    'abc  '        | 1     | 3   | 'bc'
    '  abc  '      | 1     | 3   | 'a'
    '  ab  c  '    | 2     | 6   | 'ab'
    '  ab  c  '    | 0     | 6   | 'ab'
    '      ab'     | 0     | 3   | ''
    '  ab        ' | 4     | 7   | ''
  }
}
