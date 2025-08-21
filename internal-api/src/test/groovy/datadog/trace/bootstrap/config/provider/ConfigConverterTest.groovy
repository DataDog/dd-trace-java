package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification

class ConfigConverterTest extends DDSpecification {

  def "Convert boolean properties"() {
    when:
    def value = ConfigConverter.valueOf(stringValue, Boolean)

    then:
    value == expectedConvertedValue

    where:
    stringValue | expectedConvertedValue
    "true"      | true
    "TRUE"      | true
    "True"      | true
    "1"         | true
    "false"     | false
    null        | null
    ""          | null
    "0"         | false
  }

  def "Convert boolean properties throws exception for invalid values"() {
    when:
    ConfigConverter.valueOf(invalidValue, Boolean)

    then:
    def exception = thrown(ConfigConverter.InvalidBooleanValueException)
    exception.message.contains("Invalid boolean value:")

    where:
    invalidValue << [
      "42.42",
      "tru",
      "truee",
      "true ",
      " true",
      " true ",
      "   true  ",
      "notABool",
      "yes",
      "no",
      "on",
      "off"
    ]
  }

  def "parse map properly for #mapString"() {
    when:
    def result = ConfigConverter.parseMap(mapString, "test")

    then:
    result == expected

    where:
    // spotless:off
    mapString                                       | expected
    "a:1, a:2, a:3"                                 | [a: "3"]
    "a:b,c:d,e:"                                    | [a: "b", c: "d"]
    // space separated
    "a:1  a:2  a:3"                                 | [a: "3"]
    "a:b c:d e:"                                    | [a: "b", c: "d"]
    // More different string variants:
    "a:a;"                                          | [a: "a;"]
    "a:1, a:2, a:3"                                 | [a: "3"]
    "a:1  a:2  a:3"                                 | [a: "3"]
    "a:b,c:d,e:"                                    | [a: "b", c: "d"]
    "a:b c:d e:"                                    | [a: "b", c: "d"]
    "key 1!:va|ue_1,"                               | ["key 1!": "va|ue_1"]
    "key 1!:va|ue_1 "                               | ["key 1!": "va|ue_1"]
    " key1 :value1 ,\t key2:  value2"               | [key1: "value1", key2: "value2"]
    'a:b, b:c, c:d, d: e'                           | ['a': 'b', 'b': 'c', 'c': 'd', 'd': 'e']
    "key1 :value1  \t key2:  value2"                | [key1: "value1", key2: "value2"]
    "dyno:web.1 dynotype:web appname:******"        | ["dyno": "web.1", "dynotype": "web", "appname": "******"]
    "is:val:id"                                     | [is: "val:id"]
    "a:b,is:val:id,x:y"                             | [a: "b", is: "val:id", x: "y"]
    "a:b:c:d"                                       | [a: "b:c:d"]
    'fooa:barb, foob:barc, fooc: bard, food: bare,' | ['fooa': 'barb', 'foob': 'barc', 'fooc': 'bard', 'food': 'bare']
    "a:b=c=d"                                       | [a: "b=c=d"]
    // Illegal
    "a:"                                            | [:]
    "a:b,c,d"                                       | [:]
    "a:b,c,d,k:v"                                   | [:]
    ""                                              | [:]
    "1"                                             | [:]
    "a"                                             | [:]
    "a,1"                                           | [:]
    "!a"                                            | [:]
    "    "                                          | [:]
    ",,,,"                                          | [:]
    ":,:,:,:,"                                      | [:]
    ": : : : "                                      | [:]
    "::::"                                          | [:]
    'key1:val1 with_space:and_colon, key2:val2'     | [:]
    // spotless:on
  }

  def "parse map for #mapString with separator #separator"() {
    when:
    def result = ConfigConverter.parseMap(mapString, "test", separator as char)

    then:
    result == expected

    where:
    // spotless:off
    mapString                                       | separator | expected
    "a=1, a=2, a=3"                                 | '='       | [a: "3"]
    "a=b,c=d,e="                                    | '='       | [a: "b", c: "d"]
    "a;b,c;d,e;"                                    | ';'       | [a: "b", c: "d"]
    // space separated
    "a=1  a=2  a=3"                                 | '='       | [a: "3"]
    "a=b c=d e="                                    | '='       | [a: "b", c: "d"]
    // More different string variants
    "a=b=c=d"                                       | '='       | [a: "b=c=d"]
    'fooa=barb, foob=barc, fooc= bard, food= bare,' | '='       | ['fooa': 'barb', 'foob': 'barc', 'fooc': 'bard', 'food': 'bare']
    "a=b:c:d"                                       | '='       | [a: "b:c:d"]
    // Illegal
    "a="                                            | '='       | [:]
    "===="                                          | '='       | [:]
    // spotless:on
  }

  def "parsing map #mapString with List of arg separators for with key value separator #separator"() {
    //testing parsing for DD_TAGS
    setup:
    def separatorList = [',' as char, ' ' as char]

    when:
    def result = ConfigConverter.parseTraceTagsMap(mapString, separator as char, separatorList as List<Character>)

    then:
    result == expected

    where:
    // spotless:off
    mapString                                       | separator | expected
    "key1:value1,key2:value2"                       | ':'       | [key1: "value1", key2: "value2"]
    "key1:value1 key2:value2"                       | ':'       | [key1: "value1", key2: "value2"]
    "env:test aKey:aVal bKey:bVal cKey:"            | ':'       | [env: "test", aKey: "aVal", bKey: "bVal", cKey:""]
    "env:test,aKey:aVal,bKey:bVal,cKey:"            | ':'       | [env: "test", aKey: "aVal", bKey: "bVal", cKey:""]
    "env:test,aKey:aVal bKey:bVal cKey:"            | ':'       | [env: "test", aKey: "aVal bKey:bVal cKey:"]
    "env:test     bKey :bVal dKey: dVal cKey:"      | ':'       | [env: "test", bKey: "", dKey: "", dVal: "", cKey: ""]
    'env :test, aKey : aVal bKey:bVal cKey:'        | ':'       | [env: "test", aKey : "aVal bKey:bVal cKey:"]
    "env:keyWithA:Semicolon bKey:bVal cKey"         | ':'       | [env: "keyWithA:Semicolon", bKey: "bVal", cKey: ""]
    "env:keyWith:  , ,   Lots:Of:Semicolons "       | ':'       | [env: "keyWith:", Lots: "Of:Semicolons"]
    "a:b,c,d"                                       | ':'       | [a: "b", c: "", d: ""]
    "a,1"                                           | ':'       | [a: "", "1": ""]
    "a:b:c:d"                                       | ':'       | [a: "b:c:d"]
    //edge cases
    "noDelimiters"                                  | ':'       | [noDelimiters: ""]
    "            "                                  | ':'       | [:]
    ",,,,,,,,,,,,"                                  | ':'       | [:]
    ", , , , , , "                                  | ':'       | [:]
    // spotless:on
  }

  def "test parseMapWithOptionalMappings"() {
    when:
    def result = ConfigConverter.parseMapWithOptionalMappings(mapString, "test", defaultPrefix, lowercaseKeys)

    then:
    result == expected

    where:
    mapString                     | expected                               | lowercaseKeys | defaultPrefix
    "header1:one,header2:two"     | [header1: "one", header2: "two"]       | false         | ""
    "header1:one, header2:two"    | [header1: "one", header2: "two"]       | false         | ""
    "header1,header2:two"         | [header1: "header1", header2: "two"]   | false         | ""
    "Header1:one,header2:two"     | [header1: "one", header2: "two"]       | true          | ""
    "\"header1:one,header2:two\"" | ["\"header1": "one", header2: "two\""] | true          | ""
    "header1"                     | [header1: "header1"]                   | true          | ""
    ",header1:tag"                | [header1: "tag"]                       | true          | ""
    "header1:tag,"                | [header1: "tag"]                       | true          | ""
    "header:tag:value"            | [header: "tag:value"]                  | true          | ""
    ""                            | [:]                                    | true          | ""
    null                          | [:]                                    | true          | ""
    // Test for wildcard header tags
    "*"                           | ["*":"datadog.response.headers."]      | true          | "datadog.response.headers"
    "*:"                          | [:]                                    | true          | "datadog.response.headers"
    "*,header1:tag"               | ["*":"datadog.response.headers."]      | true          | "datadog.response.headers"
    "header1:tag,*"               | ["*":"datadog.response.headers."]      | true          | "datadog.response.headers"
    // logs warning: Illegal key only tag starting with non letter '1header'
    "1header,header2:two"         | [:]                                    | true          | ""
    // logs warning: Illegal tag starting with non letter for key 'header'
    "header::tag"                 | [:]                                    | true          | ""
    // logs warning: Illegal empty key at position 0
    ":tag"                        | [:]                                    | true          | ""
    // logs warning: Illegal empty key at position 11
    "header:tag,:tag"             | [:]                                    | true          | ""
  }
}
