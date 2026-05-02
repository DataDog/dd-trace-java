package datadog.trace.util

import datadog.trace.test.util.DDSpecification

class NumbersTest extends DDSpecification {

  def "parseNumber with valid integers: #description"() {
    when:
    def result = Numbers.parseNumber(input)

    then:
    result == expected
    result instanceof Long

    where:
    description                  | input                     | expected
    'zero'                       | '0'                       | 0L
    'positive integer'           | '42'                      | 42L
    'negative integer'           | '-100'                    | -100L
    'integer with plus sign'     | '+999'                    | 999L
    'large valid long'           | '9223372036854775807'     | 9223372036854775807L
    'large negative long'        | '-9223372036854775808'    | -9223372036854775808L
  }

  def "parseNumber with valid decimals: #description"() {
    when:
    def result = Numbers.parseNumber(input)

    then:
    result == expected
    result instanceof Double

    where:
    description                  | input           | expected
    'simple decimal'             | '3.14'          | 3.14d
    'negative decimal'           | '-0.5'          | -0.5d
    'decimal with plus sign'     | '+99.99'        | 99.99d
    'zero decimal'               | '0.0'           | 0.0d
    'decimal with many digits'   | '123.456789'    | 123.456789d
    'decimal starts with dot'    | '.5'            | 0.5d
    'decimal ends with dot'      | '5.'            | 5.0d
  }

  def "parseNumber with scientific notation: #description"() {
    when:
    def result = Numbers.parseNumber(input)

    then:
    result == expected
    result instanceof Double

    where:
    description                           | input        | expected
    'scientific notation lowercase'       | '1e10'       | 1.0e10d
    'scientific notation uppercase'       | '1E10'       | 1.0E10d
    'scientific with decimal'             | '1.5e10'     | 1.5e10d
    'scientific negative exponent'        | '3.5E-7'     | 3.5E-7d
    'scientific with positive exp sign'   | '-2.5e+3'    | -2.5e+3d
    'scientific integer base'             | '5e3'        | 5000.0d
  }

  def "parseNumber with whitespace: #description"() {
    when:
    def result = Numbers.parseNumber(input)

    then:
    result == expected

    where:
    description                  | input           | expected
    'leading whitespace'         | ' 42'           | 42L
    'trailing whitespace'        | '42 '           | 42L
    'both whitespace'            | ' 42 '          | 42L
    'tab and newline'            | '\t100\n'       | 100L
    'multiple spaces decimal'    | '  -3.14  '     | -3.14d
  }

  def "parseNumber with invalid input: #description"() {
    when:
    def result = Numbers.parseNumber(input)

    then:
    result == null

    where:
    description                  | input
    'null value'                 | null
    'empty string'               | ''
    'whitespace only'            | '   '
    'alphabetic string'          | 'abc'
    'alphanumeric string'        | '12x34'
    'multiple decimals'          | '3.14.15'
    'multiple signs'             | '+-5'
    'sign only plus'             | '+'
    'sign only minus'            | '-'
    'hexadecimal'                | '0x10'
    'binary'                     | '0b1010'
    'dot only'                   | '.'
    'multiple dots'              | '...'
    'comma separator'            | '1,000'
    'currency symbol'            | '$100'
  }

  def "parseNumber with overflow: #description"() {
    when:
    def result = Numbers.parseNumber(input)

    then:
    result == null

    where:
    description                  | input
    'long overflow positive'     | '9223372036854775808'
    'long overflow negative'     | '-9223372036854775809'
    'very large number'          | '99999999999999999999999'
  }

  def "parseNumber returns correct type based on input format"() {
    expect:
    // Integers return Long
    Numbers.parseNumber('42') instanceof Long
    Numbers.parseNumber('-100') instanceof Long

    // Decimals return Double
    Numbers.parseNumber('3.14') instanceof Double
    Numbers.parseNumber('.5') instanceof Double

    // Scientific notation returns Double
    Numbers.parseNumber('1e10') instanceof Double
    Numbers.parseNumber('5E-3') instanceof Double
  }

  def "parseNumber handles edge cases for type checking"() {
    when:
    def intResult = Numbers.parseNumber('42')
    def doubleResult = Numbers.parseNumber('3.14')
    def sciResult = Numbers.parseNumber('1e10')

    then:
    // Can extract values with proper casting
    intResult == 42L
    doubleResult == 3.14d
    sciResult == 1.0e10d

    // Check types
    intResult instanceof Long
    doubleResult instanceof Double
    sciResult instanceof Double
  }
}
