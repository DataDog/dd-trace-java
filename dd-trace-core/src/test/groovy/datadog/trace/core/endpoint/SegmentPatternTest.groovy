package datadog.trace.core.endpoint

import spock.lang.Specification

class SegmentPatternTest extends Specification {

  def "INTEGER pattern matches integers with 2+ digits"() {
    expect:
    SegmentPattern.INTEGER.matches(input) == expected

    where:
    input       | expected
    "123"       | true
    "9876"      | true
    "10"        | true
    "99"        | true
    // Edge cases - should NOT match
    "1"         | false   // single digit
    "0"         | false   // zero
    "01"        | false   // leading zero
    "00123"     | false   // leading zeros
    "123abc"    | false   // contains letters
    "abc"       | false   // no digits
    ""          | false   // empty
  }

  def "INT_ID pattern matches mixed strings with digits and delimiters"() {
    expect:
    SegmentPattern.INT_ID.matches(input) == expected

    where:
    input         | expected
    "123"         | true
    "1-2-3"       | true
    "999_888"     | true
    "12.34.56"    | true
    "1_2_3_4"     | true
    // Edge cases - should NOT match
    "12"          | false   // too short (< 3 chars)
    "abc"         | false   // no digits
    "---"         | false   // no digits
    "user-123"    | false   // contains letters
    "order_456"   | false   // contains letters
    "item.789"    | false   // contains letters
    "a1b"         | false   // contains letters
    "123abc"      | false   // contains letters
    ""            | false   // empty
  }

  def "HEX pattern matches hexadecimal strings with at least one decimal digit"() {
    expect:
    SegmentPattern.HEX.matches(input) == expected

    where:
    input         | expected
    "abc123"      | true
    "123ABC"      | true
    "deadbeef0"   | true
    "A1B2C3"      | true
    "0123456789"  | true
    "abcdef0"     | true
    // Edge cases - should NOT match
    "ABCDEF"      | false   // no decimal digit (0-9)
    "abcdef"      | false   // no decimal digit
    "12345"       | false   // too short (< 6 chars)
    "abc12"       | false   // too short
    "xyz123"      | false   // contains non-hex letters
    "abc-123"     | false   // contains delimiter
    ""            | false   // empty
  }

  def "HEX_ID pattern matches hex+delimiter strings with at least one decimal digit"() {
    expect:
    SegmentPattern.HEX_ID.matches(input) == expected

    where:
    input           | expected
    "abc-123"       | true
    "def_456"       | true
    "A1B2C3"        | true
    "abc.123.def"   | true
    "0-1-2-3-4-5"   | true
    "aaa-bbb-111"   | true
    // Edge cases - should NOT match
    "abc12"         | false   // too short (< 6 chars)
    "ABCDEF"        | false   // no decimal digit
    "uuid_def456"   | false   // contains non-hex letter 'u'
    "abc-xyz"       | false   // contains non-hex letters
    "123xyz"        | false   // contains non-hex letters
    ""              | false   // empty
  }

  def "STRING pattern matches long strings or strings with special characters"() {
    expect:
    SegmentPattern.STRING.matches(input) == expected

    where:
    input                                    | expected
    // Long strings (20+ chars)
    "a" * 20                                 | true
    "very-long-string-with-many-characters"  | true
    "12345678901234567890"                   | true
    // Special characters
    "param%20value"                          | true
    "user&admin"                             | true
    "it's"                                   | true
    "func(arg)"                              | true
    "val*2"                                  | true
    "a+b"                                    | true
    "a,b"                                    | true
    "key:value"                              | true
    "a=b"                                    | true
    "user@example"                           | true
    // Edge cases - should NOT match
    "short"                                  | false   // < 20 chars, no special chars
    "123"                                    | false
    "abc-def"                                | false   // dash is not special
    ""                                       | false   // empty
  }

  def "simplify applies patterns in correct order"() {
    expect:
    SegmentPattern.simplify(input) == expected

    where:
    input                                    | expected
    // INTEGER (highest priority)
    "123"                                    | "{param:int}"
    "9876"                                   | "{param:int}"
    // INT_ID
    "1-2-3"                                  | "{param:int_id}"
    "999_888"                                | "{param:int_id}"
    // HEX
    "abc123def"                              | "{param:hex}"
    "DEAD0BEEF"                              | "{param:hex}"
    // HEX_ID
    "abc-123"                                | "{param:hex_id}"
    "def_456"                                | "{param:hex_id}"
    // STRING
    "very-long-string-with-many-characters"  | "{param:str}"
    "param%20value"                          | "{param:str}"
    // No match - keep original
    "users"                                  | "users"
    "orders"                                 | "orders"
    "v1"                                     | "v1"
    "api"                                    | "api"
    "user-123"                               | "user-123"  // has letters
    "uuid-abc123"                            | "uuid-abc123"  // has 'u' which is not hex
    // Edge cases
    ""                                       | ""
    null                                     | null
  }

  def "pattern priority ensures correct replacement"() {
    expect:
    // "123" could match INT_ID but INTEGER has priority
    SegmentPattern.simplify("123") == "{param:int}"

    // "abc123" could match HEX_ID but HEX has priority (no delimiters)
    SegmentPattern.simplify("abc123") == "{param:hex}"

    // "abc-123" matches HEX_ID (has delimiter)
    SegmentPattern.simplify("abc-123") == "{param:hex_id}"

    // Very long string matches STRING (length priority)
    SegmentPattern.simplify("a" * 20) == "{param:str}"

    // Numeric string with delimiters matches INT_ID
    SegmentPattern.simplify("1-2-3") == "{param:int_id}"
  }

  def "all enum values have valid patterns and replacements"() {
    expect:
    SegmentPattern.values().each { pattern ->
      assert pattern.getReplacement() != null
      assert pattern.getReplacement().startsWith("{param:")
      assert pattern.getReplacement().endsWith("}")
    }
  }
}
