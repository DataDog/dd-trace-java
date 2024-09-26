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
    pattern                                         | path
    p().name("foo").name("bar").build()             | p().name("foo").name("bar").build()
    p().name("foo").name("bar").name("baz").build() | p().name("foo").name("bar").name("baz").build()
    p().name("foo").anyChild().name("baz").build()  | p().name("foo").name("bar").name("baz").build()
    p().name("foo").anyChild().name("baz").build()  | p().name("foo").index(42).name("baz").build()
    p().anyChild().build()                          | p().name("phoneNumbers").build()
    p().anyChild().anyChild().build()               | p().name("foo").name("bar").build()
    p().name("keys").index(3).build()               | p().name("keys").index(3).build()
    p().name("keys").index(3).name("b").build()     | p().name("keys").index(3).name("b").build()
    p().anyDesc().name("password").build()          | p().name("foo").name("bar").index(33).name("password").build()
    p().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").build()
    p().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").index(33).name("bar").build()
    p().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").index(33).name("bar").build()
    p().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").index(33).name("bar").build()
    p().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("baz").index(33).name("bar").build()
    p().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("code").build()                         | p().name("number").name("area").name("code").build()
    p().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("code").build()                         | p().index(2).name("number").name("props").index(0).name("area").name("code").build()
  }

  def "non matching"() {
    expect:
    !pattern.matches(path)

    where:
    pattern                                         | path
    p().name("foo").name("bar").build()             | p().name("foo").name("Bar").build()
    p().name("foo").name("bar").name("baz").build() | p().name("foo").name("bar").build()
    p().name("foo").anyChild().build()              | p().name("bar").name("baz").build()
    p().name("foo").anyChild().name("baz").build()  | p().name("Foo").name("bar").name("baz").build()
    p().name("foo").anyChild().name("baz").build()  | p().name("foo").name("bar").name("Baz").build()
    p().name("foo").anyChild().name("baz").build()  | p().name("foo").name("bar").index(3).name("baz").build()
    p().name("foo").anyChild().name("baz").build()  | p().name("foo").name("bar").name("bar").name("baz").build()
    p().anyChild().build()                          | p().name("foo").name("bar").build()
    p().anyChild().anyChild().build()               | p().name("foo").build()
    p().anyChild().anyChild().build()               | p().name("foo").name("bar").name("baz").build()
    p().name("keys").index(3).build()               | p().name("keys").index(4).build()
    p().name("keys").index(3).build()               | p().name("keys").name("3").build()
    p().name("keys").index(3).name("b").build()     | p().name("keys").index(3).name("a").build()
    p().name("keys").index(3).name("b").build()     | p().name("keys").index(5).name("b").build()
    p().name("keyz").index(3).name("b").build()     | p().name("keys").index(5).name("b").build()
    p().anyDesc().name("password").build()          | p().name("foo").name("bar").index(33).name("Password").build()
    p().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").index(33).name("baz").build()
    p().anyDesc().name("number").anyDesc()
      .name("AREA").anyDesc()
      .name("code").build()                         | p().name("number").name("area").name("code").build()
    p().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("CODE").build()                         | p().index(2).name("number").name("props").index(0).name("area").name("code").build()
  }

  def "reallocate when not enough capacity"() {
    def b = JsonPath.Builder.start(1)

    expect:
    b.jsonPath().toString() == '$'

    when:
    b.name("foo")

    then:
    b.jsonPath().toString() == "\$['foo']"
  }

  def "print normalized json path"() {
    expect:
    pattern.toString() == normalized

    where:
    pattern                                                                | normalized
    p().name("phoneNumbers").anyChild().name("number").build()             | '''$['phoneNumbers'][*]['number']'''
    p().name("phoneNumbers").anyChild().name("number").build()             | '''$['phoneNumbers'][*]['number']'''
    p().name("phoneNumbers").anyChild().name("number").build()             | '''$['phoneNumbers'][*]['number']'''
    p().anyDesc().name("number").build()                                   | '''$..['number']'''
    p().name("foo").anyDesc().name("bar").anyDesc().name("number").build() | '''$['foo']..['bar']..['number']'''
    p().name("phoneNumbers").index(3).name("number").build()               | '''$['phoneNumbers'][3]['number']'''
    p().name("phoneNumbers").name("3").name("number").build()              | '''$['phoneNumbers']['3']['number']'''
  }

  def "print json path in dot notation with a custom prefix"() {
    StringBuilder prefix = new StringBuilder("dd")

    expect:
    pattern.dotted(prefix) == expected

    where:
    pattern                                                                | expected
    p().name("phoneNumbers").anyChild().name("number").build()             | 'dd.phoneNumbers.*.number'
    p().anyDesc().name("number").build()                                   | 'dd..number'
    p().name("foo").anyDesc().name("bar").anyDesc().name("number").build() | 'dd.foo..bar..number'
    p().name("phoneNumbers").index(3).name("number").build()               | 'dd.phoneNumbers.3.number'
    p().name("phoneNumbers").name("3").name("number").build()              | 'dd.phoneNumbers.3.number'
  }
}
