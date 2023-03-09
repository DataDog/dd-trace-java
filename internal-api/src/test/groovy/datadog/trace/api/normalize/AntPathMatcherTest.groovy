package datadog.trace.api.normalize


import spock.lang.Shared
import spock.lang.Specification

class AntPathMatcherTest extends Specification {
  @Shared
  AntPathMatcher matcher = new AntPathMatcher()

  def testMatch() {
    when:
    boolean match = matcher.match(pattern, path)

    then:
    match == expected

    where:
    pattern               | path                                                  | expected
    // test exact matching
    "test"                | "test"                                                | true
    "/test"               | "/test"                                               | true
    "https://example.org" | "https://example.org"                                 | true
    "/test.jpg"           | "test.jpg"                                            | false
    "test"                | "/test"                                               | false
    "/test"               | "test"                                                | false

    // test matching with ?'s
    "t?st"                | "test"                                                | true
    "??st"                | "test"                                                | true
    "tes?"                | "test"                                                | true
    "te??"                | "test"                                                | true
    "?es?"                | "test"                                                | true
    "tes?"                | "tes"                                                 | false
    "tes?"                | "testt"                                               | false
    "tes?"                | "tsst"                                                | false

    // test matching with *'s
    "*"                   | "test"                                                | true
    "test*"               | "test"                                                | true
    "test*"               | "testTest"                                            | true
    "test/*"              | "test/Test"                                           | true
    "test/*"              | "test/t"                                              | true
    "test/*"              | "test/"                                               | true
    "*test*"              | "AnothertestTest"                                     | true
    "*test"               | "Anothertest"                                         | true
    "*.*"                 | "test."                                               | true
    "*.*"                 | "test.test"                                           | true
    "*.*"                 | "test.test.test"                                      | true
    "test*aaa"            | "testblaaaa"                                          | true
    "test*"               | "tst"                                                 | false
    "test*"               | "tsttest"                                             | false
    "test*"               | "test/"                                               | false
    "test*"               | "test/t"                                              | false
    "test/*"              | "test"                                                | false
    "*test*"              | "tsttst"                                              | false
    "*test"               | "tsttst"                                              | false
    "*.*"                 | "tsttst"                                              | false
    "test*aaa"            | "test"                                                | false
    "test*aaa"            | "testblaaab"                                          | false

    // test matching with ?'s and /'s
    "/?"                  | "/a"                                                  | true
    "/?/a"                | "/a/a"                                                | true
    "/a/?"                | "/a/b"                                                | true
    "/??/a"               | "/aa/a"                                               | true
    "/a/??"               | "/a/bb"                                               | true
    "/?"                  | "/a"                                                  | true

    // test matching with **'s
    "/**"                 | "/testing/testing"                                    | true
    "/*/**"               | "/testing/testing"                                    | true
    "/**/*"               | "/testing/testing"                                    | true
    "/bla/**/bla"         | "/bla/testing/testing/bla"                            | true
    "/bla/**/bla"         | "/bla/testing/testing/bla/bla"                        | true
    "/**/test"            | "/bla/bla/test"                                       | true
    "/bla/**/**/bla"      | "/bla/bla/bla/bla/bla/bla"                            | true
    "/bla*bla/test"       | "/blaXXXbla/test"                                     | true
    "/*bla/test"          | "/XXXbla/test"                                        | true
    "/bla*bla/test"       | "/blaXXXbl/test"                                      | false
    "/*bla/test"          | "XXXblab/test"                                        | false
    "/*bla/test"          | "XXXbl/test"                                          | false

    "/????"               | "/bala/bla"                                           | false
    "/**/*bla"            | "/bla/bla/bla/bbb"                                    | false
    "/*bla*/**/bla/**"    | "/XXXblaXXXX/testing/testing/bla/testing/testing/"    | true
    "/*bla*/**/bla/*"     | "/XXXblaXXXX/testing/testing/bla/testing"             | true
    "/*bla*/**/bla/**"    | "/XXXblaXXXX/testing/testing/bla/testing/testing"     | true
    "/*bla*/**/bla/**"    | "/XXXblaXXXX/testing/testing/bla/testing/testing.jpg" | true
    "*bla*/**/bla/**"     | "XXXblaXXXX/testing/testing/bla/testing/testing/"     | true
    "*bla*/**/bla/*"      | "XXXblaXXXX/testing/testing/bla/testing"              | true
    "*bla*/**/bla/**"     | "XXXblaXXXX/testing/testing/bla/testing/testing"      | true
    "*bla*/**/bla/*"      | "XXXblaXXXX/testing/testing/bla/testing/testing"      | false
    "/x/x/**/bla"         | "/x/x/x/"                                             | false
    "/foo/bar/**"         | "/foo/bar"                                            | true
    ""                    | ""                                                    | true
  }
}
