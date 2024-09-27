package datadog.trace.payloadtags.json

import spock.lang.Specification

class JsonPointerSpec extends Specification {

  static p() {
    return new JsonPointer(10)
  }

  def "print json path in dot notation with a custom prefix"() {
    expect:
    pattern.dotted("dd") == expected

    where:
    pattern                                           | expected
    p().name("phoneNumbers").index(10).name("number") | 'dd.phoneNumbers.10.number'
    p().name("number")                                | 'dd.number'
    p().name("foo").name("bar").name("number")        | 'dd.foo.bar.number'
    p().name("phoneNumbers").index(3).name("number")  | 'dd.phoneNumbers.3.number'
    p().name("phoneNumbers").name("3").name("number") | 'dd.phoneNumbers.3.number'
  }


  def "advance pointer in array"() {
    def p = p()

    when:
    p.beginArray()

    then:
    p.dotted("") == ".0"

    when:
    p.endValue()

    then:
    p.dotted("") == ".1"

    when:
    p.endValue()

    then:
    p.dotted("") == ".2"

    when:
    p.beginArray()

    then:
    p.dotted("") == ".2.0"

    when:
    p.endValue()

    then:
    p.dotted("") == ".2.1"

    when:
    p.endArray()

    then:
    p.dotted("") == ".2"

    when:
    p.endArray()

    then:
    p.dotted("") == ""
  }

  def "advance pointer in object"() {
    def p = p()

    when:
    p.name("foo")

    then:
    p.dotted("") == ".foo"

    when:
    p.endValue()

    then:
    p.dotted("") == ""

    when:
    p.name("bar")

    then:
    p.dotted("") == ".bar"

    when:
    p.name("baz")

    then:
    p.dotted("") == ".bar.baz"

    when:
    p.endValue()

    then:
    p.dotted("") == ".bar"

    when:
    p.endValue()

    then:
    p.dotted("") == ""
  }
}
