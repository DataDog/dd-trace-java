package datadog.trace.util

import datadog.trace.test.util.DDSpecification

class StringsTest extends DDSpecification {

  def "test join strings"() {
    when:
    String s = Strings.join(joiner, strings)
    then:
    s == expected
    where:
    joiner | strings         | expected
    ","    | ["a", "b", "c"] | "a,b,c"
    ","    | ["a", "b"]      | "a,b"
    ","    | ["a"]           | "a"
    ","    | []              | ""
  }

  def "test join strings varargs"() {
    when:
    // apparently groovy doesn't like this but it runs as if it's java
    String s = Strings.join(joiner, strings.toArray(new CharSequence[0]))
    then:
    s == expected
    where:
    joiner | strings         | expected
    ","    | ["a", "b", "c"] | "a,b,c"
    ","    | ["a", "b"]      | "a,b"
    ","    | ["a"]           | "a"
    ","    | []              | ""
  }
}
