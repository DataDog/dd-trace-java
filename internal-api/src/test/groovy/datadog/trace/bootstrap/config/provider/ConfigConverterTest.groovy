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
}
