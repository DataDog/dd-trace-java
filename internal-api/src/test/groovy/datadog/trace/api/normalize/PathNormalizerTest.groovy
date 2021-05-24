package datadog.trace.api.normalize

import datadog.trace.test.util.DDSpecification

class PathNormalizerTest extends DDSpecification {

  def "pulls path from url #input"() {
    when:
    def path = PathNormalizer.normalize(input) as String

    then:
    path == expected

    where:
    input                                                            | expected
    ""                                                               | "/"
    "/"                                                              | "/"
    "/search"                                                        | "/search"
    "/users/?/:name"                                                 | "/users/?/:name"
    "abc"                                                            | "abc"
    "   "                                                            | "/"
    "   /:userId"                                                    | "/:userId"
    "\t/90"                                                          | "/?"
    "\t/:userId"                                                     | "/:userId"
  }

  def "should replace all digits"() {
    when:
    def norm = PathNormalizer.normalize(input) as String

    then:
    norm == output

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
    def norm = PathNormalizer.normalize(input) as String

    then:
    norm == output

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
    def norm = PathNormalizer.normalize(input) as String

    then:
    norm == input

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
}
