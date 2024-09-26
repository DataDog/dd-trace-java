package datadog.trace.payloadtags.json

import spock.lang.Specification

class JsonPathParserTest extends Specification {

  static p() {
    return JsonPath.Builder.start()
  }

  def "parse correct json-path patterns"() {
    expect:
    JsonPathParser.parse(path).toString() == expected.toString()

    where:
    path                            | expected
    '$.a'                           | p().name("a").build()
    '$.BarFoo'                      | p().name("BarFoo").build()
    '$.*'                           | p().any().build()
    '$[*]'                          | p().any().build()
    '$[* ]'                         | p().any().build()
    '$[ *]'                         | p().any().build()
    '$..b'                          | p().search().name("b").build()
    '$..*'                          | p().search().any().build()
    '$..fooBar'                     | p().search().name("fooBar").build()
    '$..[*]'                        | p().search().any().build()
    '$..[0]'                        | p().search().index(0).build()
    '$.phoneNumbers.*.number'       | p().name("phoneNumbers").any().name("number").build()
    '$.phoneNumbers[*].number'      | p().name("phoneNumbers").any().name("number").build()
    '$..number'                     | p().search().name("number").build()
    '$.foo..bar..number'            | p().name("foo").search().name("bar").search().name("number").build()
    '$["foo"]..["bar"]..["number"]' | p().name("foo").search().name("bar").search().name("number").build()
    '$.phoneNumbers[3].number'      | p().name("phoneNumbers").index(3).name("number").build()
    '$.phoneNumbers["3"].number'    | p().name("phoneNumbers").name("3").name("number").build()
    '$.phoneNumbers[ "3" ].number'  | p().name("phoneNumbers").name("3").name("number").build()
    '$.*.*'                         | p().any().any().build()
    '$[" a"]'                       | p().name(" a").build()
    '$["a "]'                       | p().name("a ").build()
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
