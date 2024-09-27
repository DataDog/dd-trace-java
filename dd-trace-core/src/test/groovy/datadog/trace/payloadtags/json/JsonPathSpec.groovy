package datadog.trace.payloadtags.json

import spock.lang.Specification

class JsonPathSpec extends Specification {

  static jp() {
    return JsonPath.Builder.start()
  }

  static p() {
    return new JsonPointer(10)
  }

  def "matching"() {
    expect:
    pattern.matches(path)

    where:
    pattern                                          | path
    jp().name("foo").name("bar").build()             | p().name("foo").name("bar")
    jp().name("foo").name("bar").name("baz").build() | p().name("foo").name("bar").name("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().name("foo").name("bar").name("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().name("foo").index(42).name("baz")
    jp().anyChild().build()                          | p().name("phoneNumbers")
    jp().anyChild().anyChild().build()               | p().name("foo").name("bar")
    jp().name("keys").index(3).build()               | p().name("keys").index(3)
    jp().name("keys").index(3).name("b").build()     | p().name("keys").index(3).name("b")
    jp().anyDesc().name("password").build()          | p().name("foo").name("bar").index(33).name("password")
    jp().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar")
    jp().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").index(33).name("bar")
    jp().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").index(33).name("bar")
    jp().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").index(33).name("bar")
    jp().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("baz").index(33).name("bar")
    jp().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("code").build()                          | p().name("number").name("area").name("code")
    jp().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("code").build()                          | p().index(2).name("number").name("props").index(0).name("area").name("code")
  }

  def "non matching"() {
    expect:
    !pattern.matches(path)

    where:
    pattern                                          | path
    jp().name("foo").name("bar").build()             | p().name("foo").name("Bar")
    jp().name("foo").name("bar").name("baz").build() | p().name("foo").name("bar")
    jp().name("foo").anyChild().build()              | p().name("bar").name("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().name("Foo").name("bar").name("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().name("foo").name("bar").name("Baz")
    jp().name("foo").anyChild().name("baz").build()  | p().name("foo").name("bar").index(3).name("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().name("foo").name("bar").name("bar").name("baz")
    jp().anyChild().build()                          | p().name("foo").name("bar")
    jp().anyChild().anyChild().build()               | p().name("foo")
    jp().anyChild().anyChild().build()               | p().name("foo").name("bar").name("baz")
    jp().name("keys").index(3).build()               | p().name("keys").index(4)
    jp().name("keys").index(3).build()               | p().name("keys").name("3")
    jp().name("keys").index(3).name("b").build()     | p().name("keys").index(3).name("a")
    jp().name("keys").index(3).name("b").build()     | p().name("keys").index(5).name("b")
    jp().name("keyz").index(3).name("b").build()     | p().name("keys").index(5).name("b")
    jp().anyDesc().name("password").build()          | p().name("foo").name("bar").index(33).name("Password")
    jp().name("foo").anyDesc().name("bar").build()   | p().name("foo").name("bar").index(33).name("baz")
    jp().anyDesc().name("number").anyDesc()
      .name("AREA").anyDesc()
      .name("code").build()                          | p().name("number").name("area").name("code")
    jp().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("CODE").build()                          | p().index(2).name("number").name("props").index(0).name("area").name("code")
  }

  def "print normalized json path"() {
    expect:
    pattern.toString() == normalized

    where:
    pattern                                                                 | normalized
    jp().name("phoneNumbers").anyChild().name("number").build()             | '''$['phoneNumbers'][*]['number']'''
    jp().name("phoneNumbers").anyChild().name("number").build()             | '''$['phoneNumbers'][*]['number']'''
    jp().name("phoneNumbers").anyChild().name("number").build()             | '''$['phoneNumbers'][*]['number']'''
    jp().anyDesc().name("number").build()                                   | '''$..['number']'''
    jp().name("foo").anyDesc().name("bar").anyDesc().name("number").build() | '''$['foo']..['bar']..['number']'''
    jp().name("phoneNumbers").index(3).name("number").build()               | '''$['phoneNumbers'][3]['number']'''
    jp().name("phoneNumbers").name("3").name("number").build()              | '''$['phoneNumbers']['3']['number']'''
  }
}
