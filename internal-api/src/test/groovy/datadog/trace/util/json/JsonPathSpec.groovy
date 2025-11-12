package datadog.trace.util.json

import spock.lang.Specification

class JsonPathSpec extends Specification {

  static jp() {
    return JsonPath.Builder.start()
  }

  static p() {
    return new PathCursor(10)
  }

  def "matching"() {
    expect:
    pattern.matches(path)

    where:
    pattern                                          | path
    jp().name("foo").name("bar").build()             | p().push("foo").push("bar")
    jp().name("foo").name("bar").name("baz").build() | p().push("foo").push("bar").push("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().push("foo").push("bar").push("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().push("foo").push(42).push("baz")
    jp().anyChild().build()                          | p().push("phoneNumbers")
    jp().anyChild().anyChild().build()               | p().push("foo").push("bar")
    jp().name("keys").index(3).build()               | p().push("keys").push(3)
    jp().name("keys").index(3).name("b").build()     | p().push("keys").push(3).push("b")
    jp().anyDesc().name("password").build()          | p().push("foo").push("bar").push(33).push("password")
    jp().name("foo").anyDesc().name("bar").build()   | p().push("foo").push("bar")
    jp().name("foo").anyDesc().name("bar").build()   | p().push("foo").push("bar").push(33).push("bar")
    jp().name("foo").anyDesc().name("bar").build()   | p().push("foo").push("bar").push(33).push("bar")
    jp().name("foo").anyDesc().name("bar").build()   | p().push("foo").push("bar").push(33).push("bar")
    jp().name("foo").anyDesc().name("bar").build()   | p().push("foo").push("baz").push(33).push("bar")
    jp().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("code").build()                          | p().push("number").push("area").push("code")
    jp().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("code").build()                          | p().push(2).push("number").push("props").push(0).push("area").push("code")
  }

  def "non matching"() {
    expect:
    !pattern.matches(path)

    where:
    pattern                                          | path
    jp().name("foo").name("bar").build()             | p().push("foo").push("Bar")
    jp().name("foo").name("bar").name("baz").build() | p().push("foo").push("bar")
    jp().name("foo").anyChild().build()              | p().push("bar").push("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().push("Foo").push("bar").push("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().push("foo").push("bar").push("Baz")
    jp().name("foo").anyChild().name("baz").build()  | p().push("foo").push("bar").push(3).push("baz")
    jp().name("foo").anyChild().name("baz").build()  | p().push("foo").push("bar").push("bar").push("baz")
    jp().anyChild().build()                          | p().push("foo").push("bar")
    jp().anyChild().anyChild().build()               | p().push("foo")
    jp().anyChild().anyChild().build()               | p().push("foo").push("bar").push("baz")
    jp().name("keys").index(3).build()               | p().push("keys").push(4)
    jp().name("keys").index(3).build()               | p().push("keys").push("3")
    jp().name("keys").index(3).name("b").build()     | p().push("keys").push(3).push("a")
    jp().name("keys").index(3).name("b").build()     | p().push("keys").push(5).push("b")
    jp().name("keyz").index(3).name("b").build()     | p().push("keys").push(5).push("b")
    jp().anyDesc().name("password").build()          | p().push("foo").push("bar").push(33).push("Password")
    jp().name("foo").anyDesc().name("bar").build()   | p().push("foo").push("bar").push(33).push("baz")
    jp().anyDesc().name("number").anyDesc()
      .name("AREA").anyDesc()
      .name("code").build()                          | p().push("number").push("area").push("code")
    jp().anyDesc().name("number").anyDesc()
      .name("area").anyDesc()
      .name("CODE").build()                          | p().push(2).push("number").push("props").push(0).push("area").push("code")
  }

  def "print normalized"() {
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
