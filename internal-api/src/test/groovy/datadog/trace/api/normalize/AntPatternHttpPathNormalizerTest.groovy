package datadog.trace.api.normalize


import datadog.trace.test.util.DDSpecification

class AntPatternHttpPathNormalizerTest extends DDSpecification {
  def "verify javadoc examples work"() {
    given:
    def matchers = [
      "/com/t?st.jsp"                        : "1",
      "/com/*.jsp"                           : "2",
      "/com/**/test.jsp"                     : "3",
      "/com/datadoghq/dd-trace-java/**/*.jsp": "4",
      "/com/**/servlet/bla.jsp"              : "5",
      "/org/index.*"                         : "6",
      "/test7.htm*"                          : "7",
      "/test8.html"                          : "8",
    ]
    matchers.removeAll {
      ignoreMatchers.contains(it.getKey())
    }
    AntPatternHttpPathNormalizer pathNormalizer = new AntPatternHttpPathNormalizer(matchers)

    when:
    String result = pathNormalizer.normalize(path)

    then:
    result == normalizedPath

    where:
    //spotless:off
    path                                         | ignoreMatchers                  | normalizedPath
    "/com/test.jsp"                              | []                              | "1"
    "/com/tast.jsp"                              | []                              | "1"
    "/com/txst.jsp"                              | []                              | "1"
    "/com/test.jsp"                              | ["/com/t?st.jsp"]               | "2"
    "/com/other.jsp"                             | []                              | "2"
    "/com/test.jsp"                              | ["/com/t?st.jsp", "/com/*.jsp"] | "3"
    "/com/1/test.jsp"                            | []                              | "3"
    "/com/1/a/test.jsp"                          | []                              | "3"
    "/com/2/test.jsp"                            | []                              | "3"
    "/com/datadoghq/dd-trace-java/index.jsp"     | []                              | "4"
    "/com/datadoghq/dd-trace-java/1/index.jsp"   | []                              | "4"
    "/com/datadoghq/dd-trace-java/1/a/index.jsp" | []                              | "4"
    "/com/datadoghq/dd-trace-java/2/index.jsp"   | []                              | "4"
    "/com/servlet/bla.jsp"                       | []                              | "5"
    "/com/1/servlet/bla.jsp"                     | []                              | "5"
    "/com/1/a/servlet/bla.jsp"                   | []                              | "5"
    "/com/2/servlet/bla.jsp"                     | []                              | "5"
    "/org/index.html"                            | []                              | "6"
    "/org/index.jsp"                             | []                              | "6"
    "/test7.htm"                                 | []                              | "7"
    "/test7.html"                                | []                              | "7"
    "/test7.html5"                               | []                              | "7"
    "/test8.html"                                | []                              | "8"
    "/fail"                                      | []                              | null
    //spotless:on
  }

  def "verify counter-examples don't match"() {
    given:
    def matchers = [
      "/com/t?st.jsp"                        : "1",
      "/com/*.jsp"                           : "2",
      "/com/**/test.jsp"                     : "3",
      "/com/datadoghq/dd-trace-java/**/*.jsp": "4",
      "/com/**/servlet/bla.jsp"              : "5",
    ]
    AntPatternHttpPathNormalizer pathNormalizer = new AntPatternHttpPathNormalizer(matchers)

    when:
    String result = pathNormalizer.normalize(path)

    then:
    result == null

    where:
    path << [null, "com/test.jsp", "/com/test.jspx", "/com/datadoghq"]
  }

  def "matches exact paths without wildcards"() {
    given:
    def matchers = [
      "/exact/path": "1"
    ]
    AntPatternHttpPathNormalizer normalizer = new AntPatternHttpPathNormalizer(matchers)

    when:
    String result = normalizer.normalize("/exact/path")

    then:
    result == "1"
  }

  def "matches host-prefixed patterns"() {
    given:
    def matchers = [
      "host-a.com/oauth2/token"  : "host_a_oauth",
      "*.example.com/oauth2/*"   : "example_oauth",
      "https://secure.com/api/*" : "secure_api",
      "/oauth2/token"            : "{host}_oauth"
    ]
    AntPatternHttpPathNormalizer normalizer = new AntPatternHttpPathNormalizer(matchers)

    expect:
    normalizer.normalize(host, path, false) == expected

    where:
    host               | path            | expected
    "host-a.com"       | "/oauth2/token" | "host_a_oauth"
    "auth.example.com" | "/oauth2/token" | "example_oauth"
    "secure.com"       | "/api/v1"       | "secure_api"
    "other.com"        | "/oauth2/token" | "other.com_oauth"
    null               | "/oauth2/token" | "_oauth"
    "host-a.com"       | "/unknown/path" | null
  }

  def "keep the original path "() {
    given:
    def matchers = [
      "/test/**/*.html": "*",
      "/dev/**/*.html" : "dev"
    ]
    AntPatternHttpPathNormalizer normalizer = new AntPatternHttpPathNormalizer(matchers)

    when:
    String result = normalizer.normalize(path)

    then:
    result == normalizedPath

    where:
    path                     | normalizedPath
    "/test/foo/bar.html"     | "/test/foo/bar.html"
    "/test/foo/bar/baz.html" | "/test/foo/bar/baz.html"
    "/dev/foo/bar/baz.html"  | "dev"
  }
}
