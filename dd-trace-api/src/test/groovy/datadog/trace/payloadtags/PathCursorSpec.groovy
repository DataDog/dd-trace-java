package datadog.trace.payloadtags


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

  def "attach object to path creates a copy and never changes initial value"() {
    def p1 = p().push("path").push(2)

    when:
    def p2 = p1.withValue("foo")

    then:
    p2 != p1
    p1.attachedValue() == null
    p2.attachedValue() == "foo"

    when:
    def p3 = p2.withValue("bar")

    then:
    p3 != p2
    p2.attachedValue() == "foo"
    p3.attachedValue() == "bar"
  }

  def "copy create another object without value attached to prevent possible bugs when path reused improperly"() {
    def p = new PathCursor(10)

    when:
    def p1 = p.push("a").push(3).withValue("foobar")
    def p2 = p1.copy()

    then:
    p1 != p2
    p1.dotted("") == p2.dotted("")
    p1.attachedValue() == "foobar"
    and: "attached value is not copied"
    p2.attachedValue() == null
  }
}
