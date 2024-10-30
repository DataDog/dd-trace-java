package datadog.trace.payloadtags.json

import spock.lang.Specification

class PathCursorSpec extends Specification {

  static p() {
    return new PathCursor(10)
  }

  def "print path cursor in a dot notation with a custom prefix"() {
    expect:
    pattern.dotted("dd") == expected

    where:
    pattern                                           | expected
    p().push("phoneNumbers").push(10).push("number")  | 'dd.phoneNumbers.10.number'
    p().push("number")                                | 'dd.number'
    p().push("foo").push("bar").push("number")        | 'dd.foo.bar.number'
    p().push("phoneNumbers").push(3).push("number")   | 'dd.phoneNumbers.3.number'
    p().push("phoneNumbers").push("3").push("number") | 'dd.phoneNumbers.3.number'
    p().push("foo.bar")                               | 'dd.foo\\.bar'
  }


  def "advance cursor in array"() {
    def p = p()

    when:
    p.push(0)

    then:
    p.dotted("") == ".0"

    when:
    p.advance()

    then:
    p.dotted("") == ".1"

    when:
    p.advance()

    then:
    p.dotted("") == ".2"

    when:
    p.push(0)

    then:
    p.dotted("") == ".2.0"

    when:
    p.advance()

    then:
    p.dotted("") == ".2.1"

    when:
    p.pop()

    then:
    p.dotted("") == ".2"

    when:
    p.pop()

    then:
    p.dotted("") == ""
  }

  def "advance cursor between object fields"() {
    def p = p()

    when:
    p.push("foo")

    then:
    p.dotted("") == ".foo"

    when:
    p.advance()

    then:
    p.dotted("") == ""

    when:
    p.push("bar")

    then:
    p.dotted("") == ".bar"

    when:
    p.push("baz")

    then:
    p.dotted("") == ".bar.baz"

    when:
    p.advance()

    then:
    p.dotted("") == ".bar"

    when:
    p.advance()

    then:
    p.dotted("") == ""
  }

  def "copy create another object"() {
    when:
    def p1 = p().push("a").push(3)
    def p2 = p1.copy()

    then:
    p1 != p2
    p1.dotted("") == p2.dotted("")
  }

  def "create a cursor with bigger capacity than the path"() {
    when:
    def p1 = p().push("a").push(3).push("b")
    def path = p1.toPath()

    then:
    path == ["a", 3, "b"].toArray()

    when:
    def p2 = new PathCursor(path, 4)

    then:
    p2.push("c").dotted("") == ".a.3.b.c"
  }
}
