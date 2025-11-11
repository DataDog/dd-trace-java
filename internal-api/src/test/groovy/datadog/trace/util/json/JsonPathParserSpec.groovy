package datadog.trace.util.json

import spock.lang.Specification

class JsonPathParserSpec extends Specification {

  static jp() {
    return JsonPath.Builder.start()
  }

  def "parse lists of json-path patterns for payload tagging"() {
    expect:
    JsonPathParser.parseJsonPaths(paths).toString() == expected

    where:
    paths                    | expected
    ['$.a']                  | '[$[\'a\']]'
    ['$.a.b.c', '$.x.y.z']   | '[$[\'a\'][\'b\'][\'c\'], $[\'x\'][\'y\'][\'z\']]'
    ['$.BarFoo', "invalid"]  | '[$[\'BarFoo\']]'
    ['all']                  | '[]'
    ['all', 'all']           | '[]'
    ['invalid1', "invalid2"] | '[]'
  }

  def "parse correct json-path patterns"() {
    expect:
    JsonPathParser.parse(path).toString() == expected.toString()

    where:
    path                            | expected
    '$.a'                           | jp().name("a").build()
    '$.BarFoo'                      | jp().name("BarFoo").build()
    '$.*'                           | jp().anyChild().build()
    '$[*]'                          | jp().anyChild().build()
    '$[* ]'                         | jp().anyChild().build()
    '$[ *]'                         | jp().anyChild().build()
    '$..b'                          | jp().anyDesc().name("b").build()
    '$..*'                          | jp().anyDesc().anyChild().build()
    '$..fooBar'                     | jp().anyDesc().name("fooBar").build()
    '$..[*]'                        | jp().anyDesc().anyChild().build()
    '$..[0]'                        | jp().anyDesc().index(0).build()
    '$.phoneNumbers.*.number'       | jp().name("phoneNumbers").anyChild().name("number").build()
    '$.phoneNumbers[*].number'      | jp().name("phoneNumbers").anyChild().name("number").build()
    '$..number'                     | jp().anyDesc().name("number").build()
    '$.foo..bar..number'            | jp().name("foo").anyDesc().name("bar").anyDesc().name("number").build()
    '$["foo"]..["bar"]..["number"]' | jp().name("foo").anyDesc().name("bar").anyDesc().name("number").build()
    '$.phoneNumbers[3].number'      | jp().name("phoneNumbers").index(3).name("number").build()
    '$.phoneNumbers["3"].number'    | jp().name("phoneNumbers").name("3").name("number").build()
    '$.phoneNumbers[ "3" ].number'  | jp().name("phoneNumbers").name("3").name("number").build()
    '$.*.*'                         | jp().anyChild().anyChild().build()
    '$[" a"]'                       | jp().name(" a").build()
    '$["a "]'                       | jp().name("a ").build()
  }

  def "expected parse errors"() {
    when:
    JsonPathParser.parse(path)

    then:
    def err = thrown(JsonPathParser.ParseError)
    with(err) {
      message.contains(msg)
      position == pos
    }

    where:
    path                             | pos | msg
    ''                               | 0   | "must start with '\$"
    'c'                              | 0   | "must start with '\$"
    '$.'                             | 1   | "must not end with a '.'"
    '$..'                            | 2   | "must not end with a '.'"
    '$...'                           | 3   | "More than two '.' in a row"
    '$.a '                           | 3   | "No spaces allowed in property names."
    '$. a'                           | 2   | "No spaces allowed in property names."
    '$.f o'                          | 3   | "No spaces allowed in property names."
    '$*'                             | 0   | "JsonPath must start with"
    '$a'                             | 0   | "JsonPath must start with"
    '$foo'                           | 0   | "JsonPath must start with"
    '$['                             | 1   | "Expecting in brackets a property, an array index, or a wildcard."
    '$[ '                            | 1   | "Expecting in brackets a property, an array index, or a wildcard."
    '$[['                            | 1   | "Expecting in brackets a property, an array index, or a wildcard."
    '$[-'                            | 1   | "Expecting in brackets a property, an array index, or a wildcard."
    '$[1'                            | 1   | "Expecting in brackets a property, an array index, or a wildcard."
    '$[00'                           | 1   | "Expecting in brackets a property, an array index, or a wildcard."
    '$[\''                           | 1   | "Property has not been closed - missing closing '"
    '$["'                            | 1   | 'Property has not been closed - missing closing "'
    '$[*'                            | 3   | "Expected ']'"
    '$.**'                           | 2   | "More than one '*' in a row"
    '$[()]'                          | 1   | "Expecting in brackets a property, an array index, or a wildcard."
    '$["a"][..]["b"]'                | 6   | "Expecting in brackets a property, an array index, or a wildcard."
    '$.(@.length-1)'                 | 2   | "Expressions are not supported."
    '$.[*]'                          | 1   | "'.' can't go before '['"
    '$.["foo"]..["bar"]..["number"]' | 1   | "'.' can't go before '['"
    '$.phoneNumbers.[*].number'      | 14  | "'.' can't go before '['"
    '$.phoneNumbers.[3].number'      | 14  | "'.' can't go before '['"
    '$.a[1,2]'                       | 3   | "Expecting in brackets a property, an array index, or a wildcard."
    '$.foo[-100]'                    | 5   | "Expecting in brackets a property, an array index, or a wildcard."
    '$.foo[9999999999999999]'        | 5   | "Invalid array index. Must be an integer."
    '$["\\"]'                        | 3   | "Escape character is not supported in property name."
    '$["abc]'                        | 1   | "Property has not been closed - missing closing \""
    "\$['abc]"                       | 1   | "Property has not been closed - missing closing '"
    '$["abc'                         | 1   | "Property has not been closed - missing closing \""
    '$["a,"]'                        | 4   | "Comma is not allowed in property name"
    '$["a",]'                        | 5   | "Multiple properties are not supported"
    '$["a","b"]'                     | 5   | "Multiple properties are not supported"
    '$[,"a"]'                        | 1   | "Expecting in brackets a property, an array index, or a wildcard"
    '$["abc"'                        | 1   | "Property has not been closed - missing closing ']'"
    '$["abc" '                       | 1   | "Property has not been closed - missing closing ']'"
  }
}
