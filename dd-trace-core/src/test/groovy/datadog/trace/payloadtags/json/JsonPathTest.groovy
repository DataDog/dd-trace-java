package datadog.trace.payloadtags.json

import spock.lang.Specification

class JsonPathTest extends Specification {

  static p() {
    return JsonPath.Builder.start()
  }

  def "matching"() {
    expect:
    pattern.matches(path)

    where:
    pattern                                      | path
    p().key("foo").key("bar").build()            | p().key("foo").key("bar").build()
    p().key("foo").key("bar").key("baz").build() | p().key("foo").key("bar").key("baz").build()
    p().key("foo").any().key("baz").build()      | p().key("foo").key("bar").key("baz").build()
    p().key("foo").any().key("baz").build()      | p().key("foo").index(42).key("baz").build()
    p().any().build()                            | p().key("phoneNumbers").build()
    p().any().any().build()                      | p().key("foo").key("bar").build()
    p().key("keys").index(3).build()             | p().key("keys").index(3).build()
    p().key("keys").index(3).key("b").build()    | p().key("keys").index(3).key("b").build()
    p().search().key("password").build()         | p().key("foo").key("bar").index(33).key("password").build()
    p().key("foo").search().key("bar").build()   | p().key("foo").key("bar").build()
    p().key("foo").search().key("bar").build()   | p().key("foo").key("bar").index(33).key("bar").build()
    p().key("foo").search().key("bar").build()   | p().key("foo").key("bar").index(33).key("bar").build()
    p().key("foo").search().key("bar").build()   | p().key("foo").key("bar").index(33).key("bar").build()
    p().key("foo").search().key("bar").build()   | p().key("foo").key("baz").index(33).key("bar").build()
    p().search().key("number").search()
      .key("area").search()
      .key("code").build()                       | p().key("number").key("area").key("code").build()
    p().search().key("number").search()
      .key("area").search()
      .key("code").build()                       | p().index(2).key("number").key("props").index(0).key("area").key("code").build()
  }

  def "non matching"() {
    expect:
    !pattern.matches(path)

    where:
    pattern                                      | path
    p().key("foo").key("bar").build()            | p().key("foo").key("Bar").build()
    p().key("foo").key("bar").key("baz").build() | p().key("foo").key("bar").build()
    p().key("foo").any().build()                 | p().key("bar").key("baz").build()
    p().key("foo").any().key("baz").build()      | p().key("Foo").key("bar").key("baz").build()
    p().key("foo").any().key("baz").build()      | p().key("foo").key("bar").key("Baz").build()
    p().key("foo").any().key("baz").build()      | p().key("foo").key("bar").index(3).key("baz").build()
    p().key("foo").any().key("baz").build()      | p().key("foo").key("bar").key("bar").key("baz").build()
    p().any().build()                            | p().key("foo").key("bar").build()
    p().any().any().build()                      | p().key("foo").build()
    p().any().any().build()                      | p().key("foo").key("bar").key("baz").build()
    p().key("keys").index(3).build()             | p().key("keys").index(4).build()
    p().key("keys").index(3).build()             | p().key("keys").key("3").build()
    p().key("keys").index(3).key("b").build()    | p().key("keys").index(3).key("a").build()
    p().key("keys").index(3).key("b").build()    | p().key("keys").index(5).key("b").build()
    p().key("keyz").index(3).key("b").build()    | p().key("keys").index(5).key("b").build()
    p().search().key("password").build()         | p().key("foo").key("bar").index(33).key("Password").build()
    p().key("foo").search().key("bar").build()   | p().key("foo").key("bar").index(33).key("baz").build()
    p().search().key("number").search()
      .key("AREA").search()
      .key("code").build()                       | p().key("number").key("area").key("code").build()
    p().search().key("number").search()
      .key("area").search()
      .key("CODE").build()                       | p().index(2).key("number").key("props").index(0).key("area").key("code").build()
  }

  def "reallocate when not enough capacity"() {
    def b = JsonPath.Builder.start(1)

    expect:
    b.jsonPath().toString() == '$'

    when:
    b.key("foo")

    then:
    b.jsonPath().toString() == "\$['foo']"
  }

  def "print normalized json path"() {
    expect:
    pattern.toString() == normalized

    where:
    pattern                                                           | normalized
    p().key("phoneNumbers").any().key("number").build()               | '''$['phoneNumbers'][*]['number']'''
    p().key("phoneNumbers").any().key("number").build()               | '''$['phoneNumbers'][*]['number']'''
    p().key("phoneNumbers").any().key("number").build()               | '''$['phoneNumbers'][*]['number']'''
    p().search().key("number").build()                                | '''$..['number']'''
    p().key("foo").search().key("bar").search().key("number").build() | '''$['foo']..['bar']..['number']'''
    p().key("phoneNumbers").index(3).key("number").build()            | '''$['phoneNumbers'][3]['number']'''
    p().key("phoneNumbers").key("3").key("number").build()            | '''$['phoneNumbers']['3']['number']'''
  }

  def "print json path in dot notation with a custom prefix"() {
    StringBuilder prefix = new StringBuilder("dd")

    expect:
    pattern.dotted(prefix) == expected

    where:
    pattern                                                           | expected
    p().key("phoneNumbers").any().key("number").build()               | 'dd.phoneNumbers.*.number'
    p().search().key("number").build()                                | 'dd..number'
    p().key("foo").search().key("bar").search().key("number").build() | 'dd.foo..bar..number'
    p().key("phoneNumbers").index(3).key("number").build()            | 'dd.phoneNumbers.3.number'
    p().key("phoneNumbers").key("3").key("number").build()            | 'dd.phoneNumbers.3.number'
  }
}
