package datadog.trace.api.normalize


import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class SimpleHttpPathNormalizerTest extends DDSpecification {
  @Shared
  SimpleHttpPathNormalizer simplePathNormalizer = new SimpleHttpPathNormalizer()

  def "pulls path from url #input"() {
    when:
    def path = simplePathNormalizer.normalize(input) as String
    def pathEncoded = simplePathNormalizer.normalize(input, true) as String

    then:
    path == expected
    pathEncoded == expected

    where:
    input            | expected
    ""               | "/"
    "/"              | "/"
    "/search"        | "/search"
    "/users/?/:name" | "/users/?/:name"
    "abc"            | "abc"
    "abc%de"         | "abc%de"
    "   "            | "/"
    "   /:userId"    | "/:userId"
    "\t/90"          | "/?"
    "\t/:userId"     | "/:userId"
  }

  def "should replace all digits"() {
    when:
    def norm = simplePathNormalizer.normalize(input) as String
    def normEncoded = simplePathNormalizer.normalize(input, true) as String

    then:
    norm == output
    normEncoded == output

    where:
    input              | output
    "/1"               | "/?"
    "/9999"            | "/?"
    "/user/1"          | "/user/?"
    "/user/1/"         | "/user/?/"
    "/user/1/repo/50"  | "/user/?/repo/?"
    "/user/1/repo/50/" | "/user/?/repo/?/"
  }

  def "should replace segments with mixed-characters"() {
    when:
    def norm = simplePathNormalizer.normalize(input) as String
    def normEncoded = simplePathNormalizer.normalize(input, true) as String

    then:
    norm == output
    normEncoded == output

    where:
    input                                              | output
    "/a1/v2"                                           | "/?/v2"
    "/v3/1a"                                           | "/v3/?"
    "/V01/v9/abc/-1"                                   | "/V01/v9/abc/?"
    "/ABC/av-1/b_2/c.3/d4d/v5f/v699/7"                 | "/ABC/?/?/?/?/?/?/?"
    "/user/asdf123/repository/01234567-9ABC-DEF0-1234" | "/user/?/repository/?"
  }

  def "should leave other segments alone"() {
    when:
    def norm = simplePathNormalizer.normalize(input) as String
    def normEncoded = simplePathNormalizer.normalize(input, true) as String

    then:
    norm == input
    normEncoded == input

    where:
    input      | _
    "/v0/"     | _
    "/v10/xyz" | _
    "/a-b"     | _
    "/a_b"     | _
    "/a.b"     | _
    "/a-b/a-b" | _
    "/a_b/a_b" | _
    "/a.b/a.b" | _
  }

  def "should handle encoded strings"() {
    when:
    def normEncoded = simplePathNormalizer.normalize(input, true) as String

    then:
    normEncoded == output

    where:
    input                                                | output
    "/%AA1/v2"                                           | "/?/v2"
    "/v3/1a%BB"                                          | "/v3/?"
    "/V01/v9/abc/%CC-1"                                  | "/V01/v9/abc/?"
    "/A%DD%EE/av-1/b_2/c.3/%FFd4d/v5f/v699/7"            | "/A%DD%EE/?/?/?/?/?/?/?"
    "/user/asd%A0123/repository/01234567-9ABC-DEF0-1234" | "/user/?/repository/?"
  }
}
