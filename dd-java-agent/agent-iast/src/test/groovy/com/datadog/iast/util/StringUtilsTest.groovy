package com.datadog.iast.util

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObjects
import spock.lang.Specification

import java.util.regex.Pattern
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

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

  void 'test leadingWhitespaces method'() {
    when:
    final leadingWhitespaces = StringUtils.leadingWhitespaces(value, start, end)

    then:
    leadingWhitespaces == expected

    where:
    value     | start | end | expected
    "   abc"  | 0     | 3   | 3
    "    abc" | 0     | 3   | 3
    "  abc"   | 0     | 3   | 2
    "  abc  " | 0     | 3   | 2
  }

  void 'test replaceAndTaint method'() {
    given:
    final taintedObjects = Mock(TaintedObjects)

    when:
    final taintedString = StringUtils.replaceAndTaint(taintedObjects, target, pattern, replacement, ranges as Range[], rangesInput as Range[], numOfReplacement)

    then:
    taintedString == expected
    if (numOfReplacement > 0) {
      1 * taintedObjects.taint(expected, expectedRanges)
    } else {
      0 * taintedObjects.taint(_, _)
    }

    where:
    target               | pattern                | replacement | ranges                                                                                                        | rangesInput               | numOfReplacement  | expected             | expectedRanges
    "masquita"           | Pattern.compile('as')  | 'os'        | [range(0, 8, null, "masquita")]                                                                               | [range(0, 2, null, "os")] | Integer.MAX_VALUE | "mosquita"           | [range(0, 1, null, "masquita"), range(1, 2, null, "os"), range(3, 5, null, "masquita")]
    "masquita"           | Pattern.compile('as')  | 'os'        | [range(0, 1, null, "m"), range(3, 2, null, "qu"), range(7, 2, null, "ta")]                                    | [range(0, 2, null, "os")] | Integer.MAX_VALUE | "mosquita"           | [range(0, 1, null, "m"), range(1, 2, null, "os"), range(3, 2, null, "qu"), range(7, 2, null, "ta")]
    "my_outputmy_output" | Pattern.compile('out') | 'in'        | [
      range(0, 4, null, "my_o"),
      range(5, 4, null, "tput"),
      range(9, 4, null, "my_o"),
      range(14, 4, null, "tput")
    ] | [range(0, 2, null, "in")] | Integer.MAX_VALUE | "my_inputmy_input"   | [
      range(0, 3, null, "my_o"),
      range(3, 2, null, "in"),
      range(5, 3, null, "tput"),
      range(8, 3, null, "my_o"),
      range(11, 2, null, "in"),
      range(13, 3, null, "tput")
    ]
    "my_outputmy_output" | Pattern.compile('out') | 'in'        | [
      range(0, 4, null, "my_o"),
      range(5, 4, null, "tput"),
      range(9, 4, null, "my_o"),
      range(14, 4, null, "tput")
    ] | [range(0, 2, null, "in")] | 1                 | "my_inputmy_output"  | [
      range(0, 3, null, "my_o"),
      range(3, 2, null, "in"),
      range(5, 3, null, "tput"),
      range(8, 4, null, "my_o"),
      range(13, 4, null, "tput")
    ]
    "my_outputmy_output" | Pattern.compile('out') | 'in'        | [
      range(0, 4, null, "my_o"),
      range(5, 4, null, "tput"),
      range(9, 4, null, "my_o"),
      range(14, 4, null, "tput")
    ] | [range(0, 2, null, "in")] | 0                 | "my_outputmy_output" | [
      range(0, 4, null, "my_o"),
      range(5, 4, null, "tput"),
      range(9, 4, null, "my_o"),
      range(14, 4, null, "tput")
    ]
  }

  Range range(final int start, final int length, final String name = 'name', final String value = 'value') {
    return new Range(start, length, new Source((byte) 1, name, value), NOT_MARKED)
  }
}
