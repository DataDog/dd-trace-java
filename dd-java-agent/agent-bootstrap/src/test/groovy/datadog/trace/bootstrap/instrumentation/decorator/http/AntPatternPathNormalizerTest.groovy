package datadog.trace.bootstrap.instrumentation.decorator.http

import datadog.trace.test.util.DDSpecification

class AntPatternPathNormalizerTest extends DDSpecification {
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
    AntPatternPathNormalizer pathNormalizer = new AntPatternPathNormalizer(matchers, new FailingPathNormalizer())

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
    "/test8.html"                                | []                              | null
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
    AntPatternPathNormalizer pathNormalizer = new AntPatternPathNormalizer(matchers, new FailingPathNormalizer())

    when:
    String result = pathNormalizer.normalize(path)

    then:
    result == null

    where:
    path << [null, "com/test.jsp", "/com/test.jspx", "/com/datadoghq"]
  }

  def "ignore patterns that aren't patterns"() {
    given:
    def matchers = [
      "/not/a/pattern": "1"
    ]
    AntPatternPathNormalizer normalizer = new AntPatternPathNormalizer(matchers, new FailingPathNormalizer())

    when:
    String result = normalizer.normalize("/not/a/pattern")

    then:
    result == null
  }

  static class FailingPathNormalizer extends PathNormalizer {
    @Override
    String normalize(String path, boolean encoded) {
      return null
    }
  }
}
